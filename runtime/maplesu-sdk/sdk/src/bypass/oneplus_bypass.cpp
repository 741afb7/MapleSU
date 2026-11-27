// oneplus bypass implementation.
//
// 通过 OnePlus 内核驱动的 generic netlink 通道触发 stage1，再按 kallsyms 解析
// 三处 g_boot_state 符号并写入启动状态字节完成 stage2。两条路径都在 fork 出的
// 子进程里执行，父进程只通过 fork 管道收回错误码与子进程日志。
#include "kernel_module_kit_umbrella.h"

#include <fcntl.h>
#include <linux/genetlink.h>
#include <linux/netlink.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <thread>

#include "bypass/oneplus_bypass_private.h"
#include "private_module_selinux.h"
#include "private_maplesu_fork.h"
#include "maplesu_storage_worker_private.h"
#include "maplesu_status_query_private.h"


namespace default_modules {
namespace module_bypass_oplus_secure_guard {
bool is_work_normal();
}  // namespace module_bypass_oplus_secure_guard
}  // namespace default_modules

namespace {

// 固定文案的错误输出：不解析格式串，按字面量长度写到 stderr。
__attribute__((always_inline)) inline void print_error(const char* text) {
    fwrite(text, strlen(text), 1, stderr);
}

// 一条 nlmsg 的头部长度（不含 payload）。
constexpr size_t kNlmsgHeaderLen = sizeof(nlmsghdr);

// 与 genl 控制器交互固定使用 AF_NETLINK / NETLINK_GENERIC。
constexpr int kNlFamily = AF_NETLINK;
constexpr int kNlProtocol = NETLINK_GENERIC;
constexpr int kNlRawSocket = SOCK_RAW;

// UPLOAD 消息里驱动回传数据属性的最小期望长度。
constexpr size_t kUploadMinPayloadLen = 64;

// 与驱动约定的 genl 命令字。
constexpr uint8_t kGenlCmdSendPid = 1;
constexpr uint8_t kGenlCmdUpload = 2;

// 驱动要求的单条 SENDPID 消息长度：nlmsghdr + genlmsghdr + 一个 u32 属性。
constexpr size_t kSendPidRequestLen =
    kNlmsgHeaderLen + GENL_HDRLEN + NLA_HDRLEN + sizeof(uint32_t);

// 一次最多接收的 netlink 字节数。
constexpr size_t kNetlinkRecvBufferLen = 8192;

// 监听 UPLOAD 消息的轮询超时。
constexpr int kUploadListenTimeoutMs = 60000;

// generic netlink 客户端：持有 socket fd、最近一次解析出的 family id 和序号。
class Client {
public:
    Client() { close_socket(); }

    ~Client() { close_socket(); }

    Client(const Client&) = delete;
    Client& operator=(const Client&) = delete;

    void close_socket() {
        if (fd_ >= 0) {
            close(fd_);
            fd_ = -1;
        }
        family_id_ = 0;
    }

    bool ensure_ready() const {
        if (fd_ < 0) {
            print_error("[ERROR] socket is not open.");
            return false;
        }
        if (family_id_ == 0) {
            print_error("[ERROR] genl family is not resolved.");
            return false;
        }
        return true;
    }

    // 向 genl 控制器查询 family 名对应的数值 id；查不到返回 0。
    uint16_t resolve_family(const char* family_name) {
        if (family_name == nullptr || fd_ < 0 || family_name[0] == '\0') {
            return 0;
        }

        struct GetFamilyRequest {
            nlmsghdr nlh;
            genlmsghdr genl;
            nlattr attr;
            char name[0x1e8];
        };
        struct GetFamilyResponse {
            char data[0x1000];
        };

        GetFamilyRequest request;
        memset(&request.genl, 0, sizeof(request) - offsetof(GetFamilyRequest, genl));

        const size_t name_len = strlen(family_name);
        const size_t name_copy_len = name_len + 1;
        request.nlh.nlmsg_len = static_cast<uint32_t>(
            NLA_ALIGN(NLA_HDRLEN + name_len + 1) + kNlmsgHeaderLen + GENL_HDRLEN);
        request.nlh.nlmsg_type = GENL_ID_CTRL;
        request.nlh.nlmsg_flags = NLM_F_REQUEST;
        request.nlh.nlmsg_seq = ++seq_;
        request.nlh.nlmsg_pid = static_cast<uint32_t>(getpid());
        request.genl.cmd = CTRL_CMD_GETFAMILY;
        request.genl.version = 1;
        const size_t attr_len = NLA_HDRLEN + name_len + 1;
        request.attr.nla_len = static_cast<uint16_t>(attr_len);
        request.attr.nla_type = CTRL_ATTR_FAMILY_NAME;
        memcpy(request.name, family_name, name_copy_len);

        if (nl_send(&request, request.nlh.nlmsg_len) < 0) {
            return 0;
        }

        GetFamilyResponse response;
        memset(response.data, 0, sizeof(response.data));
        const ssize_t got = nl_recv(response.data, sizeof(response.data));
        if (got < 1) {
            return 0;
        }

        const nlmsghdr* nlh = reinterpret_cast<const nlmsghdr*>(response.data);
        int remaining = static_cast<int>(got);
        for (;;) {
            if (remaining < static_cast<int>(kNlmsgHeaderLen)) {
                return 0;
            }
            if (nlh->nlmsg_len < kNlmsgHeaderLen || nlh->nlmsg_len > remaining) {
                return 0;
            }
            const uint16_t msg_type = nlh->nlmsg_type;
            if (msg_type == NLMSG_ERROR) {
                return 0;
            }
            if (msg_type == GENL_ID_CTRL && nlh->nlmsg_len > kNlmsgHeaderLen + GENL_HDRLEN) {
                const char* attr_cursor =
                    reinterpret_cast<const char*>(nlh) + kNlmsgHeaderLen + GENL_HDRLEN;
                const size_t attr_room = nlh->nlmsg_len - kNlmsgHeaderLen - GENL_HDRLEN;
                for (size_t offset = 0; offset + NLA_HDRLEN <= attr_room;) {
                    const nlattr* attr = reinterpret_cast<const nlattr*>(attr_cursor + offset);
                    if (attr->nla_len < NLA_HDRLEN) {
                        break;
                    }
                    if (attr->nla_type == CTRL_ATTR_FAMILY_ID &&
                        (attr->nla_len & 0xFFFEu) != NLA_HDRLEN) {
                        const uint16_t family_id = *reinterpret_cast<const uint16_t*>(
                            attr_cursor + offset + NLA_HDRLEN);
                        if (family_id != 0) {
                            return family_id;
                        }
                        break;
                    }
                    offset += NLA_ALIGN(attr->nla_len);
                }
            }
            remaining -= static_cast<int>(NLMSG_ALIGN(nlh->nlmsg_len));
            nlh = reinterpret_cast<const nlmsghdr*>(reinterpret_cast<const char*>(nlh) +
                                                    NLMSG_ALIGN(nlh->nlmsg_len));
        }
    }

    ssize_t nl_send(const void* data, size_t size) const {
        sockaddr_nl address;
        memset(&address, 0, sizeof(address));
        address.nl_family = AF_NETLINK;
        const ssize_t sent = sendto(fd_, data, size, 0,
                                    reinterpret_cast<const sockaddr*>(&address),
                                    sizeof(address));
        if (sent < 0) {
            fprintf(stderr, "[ERROR] sendto: %s.\n", strerror(errno));
            return -1;
        }
        return sent;
    }

    ssize_t nl_recv(void* data, size_t size) const {
        const ssize_t got = recvfrom(fd_, data, size, 0, nullptr, nullptr);
        if (got < 0) {
            fprintf(stderr, "[ERROR] recv: %s.\n", strerror(errno));
            return -1;
        }
        return got;
    }

    int fd_ = -1;
    uint16_t family_id_ = 0;
    uint32_t seq_ = 0;
};

// 打开客户端 socket 并绑定到本进程 pid；失败时打印具体原因。
bool open_client_socket(Client& client) {
    client.fd_ = socket(kNlFamily, kNlRawSocket, kNlProtocol);
    if (client.fd_ < 0) {
        fprintf(stderr, "[ERROR] socket: %s.\n", strerror(errno));
        return false;
    }

    // Startup is acknowledged to the caller, so a silent controller must be bounded.
    const timeval timeout{3, 0};
    if (setsockopt(client.fd_, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0 ||
        setsockopt(client.fd_, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) < 0) {
        fprintf(stderr, "[ERROR] socket timeout: %s.\n", strerror(errno));
        return false;
    }
    sockaddr_nl address;
    memset(&address, 0, sizeof(address));
    address.nl_family = AF_NETLINK;
    address.nl_pid = static_cast<uint32_t>(getpid());
    if (bind(client.fd_, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) < 0) {
        fprintf(stderr, "[ERROR] bind: %s.\n", strerror(errno));
        return false;
    }
    printf("[*] Socket created, pid=%d.\n", getpid());
    return true;
}

// 依次尝试驱动暴露的两个 family 名；都失败时打印固定文案。
bool resolve_client_family(Client& client) {
    uint16_t family_id = client.resolve_family("sgtgki");
    client.family_id_ = family_id;
    if (family_id != 0) {
        printf("[*] Resolved familyGki1 -> id=%u.\n", family_id);
        return true;
    }

    family_id = client.resolve_family("secure_guard");
    client.family_id_ = family_id;
    if (family_id != 0) {
        printf("[*] Resolved familyGki2 -> id=%u.\n", family_id);
        return true;
    }

    print_error("[ERROR] Failed to resolve genl family. Is the driver loaded?.");
    return false;
}

// 向驱动发送本进程 pid，让驱动把后续 UPLOAD 消息回传给这个 netlink 端口。
bool send_pid_to_driver(Client& client) {
    struct SendPidRequest {
        nlmsghdr nlh;
        genlmsghdr genl;
        nlattr attr;
        uint32_t pid;
        char reserved[0xe8];
    };

    const int pid = getpid();
    SendPidRequest request;
    memset(reinterpret_cast<char*>(&request) + kNlmsgHeaderLen, 0,
           sizeof(request) - kNlmsgHeaderLen);

    request.nlh.nlmsg_len = static_cast<uint32_t>(kSendPidRequestLen);
    request.nlh.nlmsg_type = client.family_id_;
    request.nlh.nlmsg_flags = NLM_F_REQUEST;
    request.nlh.nlmsg_seq = ++client.seq_;
    request.nlh.nlmsg_pid = static_cast<uint32_t>(pid);
    request.genl.cmd = kGenlCmdSendPid;
    request.genl.version = 1;
    request.attr.nla_len = static_cast<uint16_t>(NLA_HDRLEN + sizeof(uint32_t));
    request.attr.nla_type = 1;
    request.pid = static_cast<uint32_t>(pid);

    printf("[*] Sending SENDPID (pid=%u)....\n", static_cast<uint32_t>(pid));
    if (client.nl_send(&request, kSendPidRequestLen) <= 0) {
        print_error("[ERROR] Failed to send PID.");
        return false;
    }
    return true;
}

// 处理一条 UPLOAD 消息里的 genl 属性（当前只做长度诊断）。
void handle_upload_attrs(const nlmsghdr* nlh) {
    const char* attr_cursor =
        reinterpret_cast<const char*>(nlh) + kNlmsgHeaderLen + GENL_HDRLEN;
    const size_t attr_room = nlh->nlmsg_len - kNlmsgHeaderLen - GENL_HDRLEN;
    for (size_t offset = 0; offset + NLA_HDRLEN <= attr_room;) {
        const nlattr* attr = reinterpret_cast<const nlattr*>(attr_cursor + offset);
        if (attr->nla_len < NLA_HDRLEN) {
            return;
        }
        const size_t payload_len = attr->nla_len - NLA_HDRLEN;
        if (attr->nla_type == 1 && payload_len < kUploadMinPayloadLen) {
            fprintf(stderr, "[WARN] UPLOAD msg too short (%zu bytes).\n", payload_len);
        }
        offset += NLA_ALIGN(attr->nla_len);
    }
}

// 轮询驱动回传的 UPLOAD 消息，直到超时或链路出错。
void listen_upload_messages(Client& client) {
    char recv_buffer[kNetlinkRecvBufferLen] = {};

    for (;;) {
        pollfd poll_fd;
        poll_fd.fd = client.fd_;
        poll_fd.events = POLLIN;
        poll_fd.revents = 0;
        printf("[*] Listening for UPLOAD messages (timeout=%ds).\n",
               kUploadListenTimeoutMs / 1000);

        const int poll_result = poll(&poll_fd, 1, kUploadListenTimeoutMs);
        if (poll_result < 0) {
            fprintf(stderr, "[ERROR] poll: %s.\n", strerror(errno));
            break;
        }
        if (poll_result == 0) {
            puts("[*] Timeout, no more messages.");
            break;
        }

        const ssize_t recv_len = client.nl_recv(recv_buffer, sizeof(recv_buffer));
        if (recv_len < 1) {
            break;
        }

        const nlmsghdr* nlh = reinterpret_cast<const nlmsghdr*>(recv_buffer);
        int remaining = static_cast<int>(recv_len);
        for (;;) {
            if (remaining < static_cast<int>(kNlmsgHeaderLen)) {
                break;
            }
            if (nlh->nlmsg_len < kNlmsgHeaderLen || nlh->nlmsg_len > remaining) {
                break;
            }
            const uint16_t msg_type = nlh->nlmsg_type;
            if (msg_type == NLMSG_ERROR) {
                printf("[WARN] NLMSG_ERROR: %d.\n",
                       reinterpret_cast<const nlmsgerr*>(nlh)->error);
            } else if (msg_type == client.family_id_) {
                if (nlh->nlmsg_len <= kNlmsgHeaderLen + GENL_HDRLEN) {
                    print_error("[WARN] Invalid short genl message.");
                } else {
                    const uint8_t genl_cmd =
                        reinterpret_cast<const genlmsghdr*>(
                            reinterpret_cast<const char*>(nlh) + kNlmsgHeaderLen)
                            ->cmd;
                    if (genl_cmd != kGenlCmdUpload) {
                        printf("[DEBUG] Ignoring cmd=%u.\n", static_cast<unsigned>(genl_cmd));
                    } else {
                        handle_upload_attrs(nlh);
                    }
                }
            } else {
                printf("[DEBUG] Ignoring msg type=%u.\n", static_cast<unsigned>(msg_type));
            }
            remaining -= static_cast<int>(NLMSG_ALIGN(nlh->nlmsg_len));
            nlh = reinterpret_cast<const nlmsghdr*>(reinterpret_cast<const char*>(nlh) +
                                                    NLMSG_ALIGN(nlh->nlmsg_len));
        }
    }
    puts("[*] Done.");
}

// The listener continues after startup is acknowledged; it owns its session until exit.
static KModErr start_stage1_netlink(Client& client) {
    struct sigaction signal_action{};
    signal_action.sa_handler = SIG_IGN;
    sigemptyset(&signal_action.sa_mask);
    sigaction(SIGPIPE, &signal_action, nullptr);
    if (!open_client_socket(client) || !resolve_client_family(client) ||
        !client.ensure_ready() || !send_pid_to_driver(client))
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    puts("[+] SENDPID success");
    return KModErr::OK;
}

static KModErr finish_worker(maplesu::sdk::fork_pipe_info& pipe, KModErr error) {
    const pid_t child = pipe.child_pid;
    pipe.close_all();
    if (child <= 0) return is_failed(error) ? error : KModErr::ERR_MODULE_TEST_CHANNEL;
    int status = 0;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (is_ok(error) && (waited != child || !WIFEXITED(status) || WEXITSTATUS(status)))
        return KModErr::ERR_READ_CHILD_ERRCODE;
    return error;
}

// 取出三处 g_boot_state 符号的内核地址（解析期间静默 kallsyms 打印）。
static KModErr resolve_oplus_guard_boot_state_targets(std::set<uint64_t>& targets) {
    static const char* const kSymbols[] = {
        "oplus_secure_guard:g_boot_state",
        "oplus_secure_guard_new:g_boot_state",
        "oplus_security_guard:g_boot_state",
    };

    SymbolLookupSilentGuard_c67c0e4fed21dd11b34c67e0a5b878c8 silent_guard;

    targets.clear();
    for (const char* symbol_name : kSymbols) {
        uint64_t kaddr = 0;
        const auto error = kernel_module::kallsyms_lookup_name(symbol_name, kaddr);
        if (error == KModErr::ERR_MODULE_SYMBOL_NOT_EXIST) continue;
        if (is_failed(error)) return error;
        if (kaddr == 0) {
            continue;
        }
        targets.insert(kaddr);
    }
    return KModErr::OK;
}

// 把 g_boot_state 首字节改成 true（先放开写保护，写完恢复只读）。
__attribute__((always_inline)) inline KModErr patch_oplus_guard_boot_state() {
    bool boot_state = true;
    std::set<uint64_t> targets;
    RETURN_IF_ERROR(resolve_oplus_guard_boot_state_targets(targets));

    KModErr patch_err = KModErr::OK;
    for (uint64_t kaddr : targets) {
        patch_err = kernel_module::set_kernel_mem_protection(
            kaddr, sizeof(boot_state), kernel_module::KernMemProt::KMP_RW);
        if (patch_err != KModErr::OK) {
            break;
        }
        patch_err = kernel_module::write_kernel_mem(
            kaddr, &boot_state, sizeof(boot_state), kernel_module::KernMemProt::KMP_RW);
        const auto restored = kernel_module::set_kernel_mem_protection(
            kaddr, sizeof(boot_state), kernel_module::KernMemProt::KMP_RO);
        if (is_ok(patch_err)) patch_err = restored;
        if (patch_err != KModErr::OK) {
            break;
        }
    }
    if (targets.empty()) {
        patch_err = KModErr::ERR_MODULE_SYMBOL_NOT_EXIST;
    }
    return patch_err;
}

}  // namespace

KModErr oneplus_bypass_stage1_and_2(void (*report_cb)(const char*)) {
    if (!kernel_module::maplesu_request_auth_usable(kernel_module::maplesu_request_auth_state()))
        return KModErr::ERR_MODULE_ROOT_KEY;
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());

    std::string stage1_report;
    std::string stage2_report;
    KModErr err;
    {
        maplesu::sdk::fork_pipe_info pipe;
        err = KModErr::ERR_MODULE_TEST_CHANNEL;
        if (maplesu::sdk::fork_pipe_child_process(pipe)) {
            const pid_t listener = fork();
            if (listener != 0) {
                if (listener < 0) {
                    const bool sent_error = maplesu::sdk::write_errcode_from_child(pipe, KModErr::ERR_MODULE_TEST_CHANNEL);
                    const bool sent_report = maplesu::sdk::write_string_from_child(pipe, stage1_report);
                    pipe.close_all();
                    _exit(sent_error && sent_report ? 0 : 1);
                }
                // The grandchild owns the same channel and reports its actual startup result.
                pipe.close_all();
                _exit(0);
            }
            if (setsid() < 0) setpgid(0, 0);
            RedirectStdToString& redirector = RedirectStdToString::instance();
            redirector.redirect(stage1_report, RedirectStdToString::OpenMode::Reset);
            maplesu::storage::WorkerScope worker;
            err = worker.root();
            Client client;
            if (is_ok(err)) err = start_stage1_netlink(client);
            if (is_failed(err)) (void)worker.close_checked();
            redirector.restore();
            const bool sent_error = maplesu::sdk::write_errcode_from_child(pipe, err);
            const bool sent_report = maplesu::sdk::write_string_from_child(pipe, stage1_report);
            pipe.close_all();
            if (!sent_error || !sent_report || is_failed(err)) {
                (void)worker.close_checked();
                _exit(1);
            }
            redirector.detach_stdio_after_fork_in_child(RedirectStdToString::ForkChildStdMode::ToDevNull);
            listen_upload_messages(client);
            const auto closed = worker.close_checked();
            fflush(nullptr);
            _exit(is_ok(closed) ? 0 : 1);
        }
        if (!maplesu::sdk::read_errcode_from_child(pipe, err)) {
            err = KModErr::ERR_READ_CHILD_ERRCODE;
        }
        if (!maplesu::sdk::read_string_from_child(pipe, stage1_report) && is_ok(err))
            err = KModErr::ERR_READ_CHILD_STRING;
        err = finish_worker(pipe, err);
    }
    if (is_failed(err)) {
        return err;
    }
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    {
        maplesu::sdk::fork_pipe_info pipe;
        err = KModErr::ERR_MODULE_TEST_CHANNEL;
        if (maplesu::sdk::fork_pipe_child_process(pipe)) {
            RedirectStdToString& redirector = RedirectStdToString::instance();
            redirector.redirect(stage2_report, RedirectStdToString::OpenMode::Reset);
            maplesu::storage::WorkerScope worker;
            err = worker.root();
            if (is_ok(err)) err = patch_oplus_guard_boot_state();
            const auto closed = worker.close_checked();
            if (is_ok(err)) err = closed;
            printf("oneplus_bypass_stage2: %s.\n", to_string(err).c_str());
            redirector.restore();
            const bool sent_error = maplesu::sdk::write_errcode_from_child(pipe, err);
            const bool sent_report = maplesu::sdk::write_string_from_child(pipe, stage2_report);
            pipe.close_all();
            _exit(sent_error && sent_report ? 0 : 1);
        }
        if (!maplesu::sdk::read_errcode_from_child(pipe, err)) {
            err = KModErr::ERR_READ_CHILD_ERRCODE;
        }
        if (!maplesu::sdk::read_string_from_child(pipe, stage2_report) && is_ok(err))
            err = KModErr::ERR_READ_CHILD_STRING;
        err = finish_worker(pipe, err);
    }
    if (is_failed(err)) {
        return err;
    }
    if (report_cb != nullptr) {
        const std::string report = stage1_report + stage2_report;
        report_cb(report.c_str());
    }
    return KModErr::OK;

}

KModErr oneplus_bypass_query_work_normal(bool& value) {
    if (!kernel_module::maplesu_request_auth_usable(kernel_module::maplesu_request_auth_state()))
        return KModErr::ERR_MODULE_ROOT_KEY;
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    maplesu::sdk::fork_pipe_info pipe;
    KModErr error = KModErr::ERR_READ_CHILD_ERRCODE;
    if (maplesu::sdk::fork_pipe_child_process(pipe)) {
        bool observed = false;
        maplesu::storage::WorkerScope worker;
        error = worker.root();
        if (is_ok(error)) error = default_modules::module_bypass_oplus_secure_guard::query_work_normal(observed);
        const auto closed = worker.close_checked();
        if (is_ok(error)) error = closed;
        const bool sent_error = maplesu::sdk::write_errcode_from_child(pipe, error);
        const bool sent_value = maplesu::sdk::write_bool_from_child(pipe, observed);
        pipe.close_all();
        _exit(sent_error && sent_value ? 0 : 1);
    }
    bool observed = false;
    if (pipe.child_pid <= 0 || !maplesu::sdk::read_errcode_from_child(pipe, error)) error = KModErr::ERR_READ_CHILD_ERRCODE;
    else if (!maplesu::sdk::read_bool_from_child(pipe, observed) && is_ok(error)) error = KModErr::ERR_READ_CHILD_ERRCODE;
    pipe.close_all();
    if (pipe.child_pid > 0) {
        int status = 0;
        pid_t waited;
        do { waited = waitpid(pipe.child_pid, &status, 0); } while (waited < 0 && errno == EINTR);
        if (is_ok(error) && (waited != pipe.child_pid || !WIFEXITED(status) || WEXITSTATUS(status)))
            error = KModErr::ERR_READ_CHILD_ERRCODE;
    }
    if (is_ok(error)) value = observed;
    return error;
}
bool oneplus_bypass_is_work_normal() {
    bool value = false;
    (void)oneplus_bypass_query_work_normal(value);
    return value;
}
