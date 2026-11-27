// Kernel scheduler/allocator/list seams for exact provider function extraction.
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <iostream>
#include <set>
#include <string>

using u64 = std::uint64_t;
using u32 = std::uint32_t;
constexpr unsigned MSU_MANAGEMENT_SLOTS = 64;
constexpr unsigned PF_EXITING = 4, GFP_ATOMIC = 1, __GFP_NOWARN = 2;
constexpr int ESHUTDOWN = 108, ESTALE = 116;
struct task_struct { unsigned flags = 0, refs = 1; };
thread_local task_struct* current = nullptr;
struct list_head { list_head *next, *prev; };
#define INIT_LIST_HEAD(head) do { (head)->next=(head); (head)->prev=(head); } while(0)
#define container_of(pointer,type,member) ((type*)((char*)(pointer)-offsetof(type,member)))
#define list_for_each_entry(position,head,member) \
    for(list_head* node_cursor=(head)->next;node_cursor!=(head)&&((position)=container_of(node_cursor,msu_private_return_owner,member),true);node_cursor=node_cursor->next)
#define list_for_each_entry_reverse(position,head,member) \
    for(list_head* node_cursor=(head)->prev;node_cursor!=(head)&&((position)=container_of(node_cursor,msu_private_return_owner,member),true);node_cursor=node_cursor->prev)
void list_add_tail(list_head* node,list_head* head) { node->prev=head->prev;node->next=head;head->prev->next=node;head->prev=node; }
void list_del_init(list_head* node) { node->prev->next=node->next;node->next->prev=node->prev;INIT_LIST_HEAD(node); }
bool list_empty(list_head* head) { return head->next==head; }
unsigned msu_management_lock;
#define raw_spin_lock_irqsave(lock,flags) do { (void)(lock);(flags)=0; } while(0)
#define raw_spin_unlock_irqrestore(lock,flags) do { (void)(lock);(void)(flags); } while(0)
#define READ_ONCE(value) (value)
#define MSU_DYN_FIELD(value,type,field) ((value)->field)
bool nmi = false, fail_allocation = false;
unsigned allocated = 0, released = 0, scheduled = 0;
std::set<void*> allocations;
bool in_nmi() { return nmi; }
struct msu_private_return_owner;
msu_private_return_owner* kmalloc(std::size_t bytes,unsigned) { if(fail_allocation)return nullptr;void* p=std::malloc(bytes);assert(p);allocations.insert(p);++allocated;return static_cast<msu_private_return_owner*>(p); }
void kfree(void* p) { if(!p)return;assert(allocations.erase(p));++released;std::free(p); }
void get_task_struct(task_struct* task) { ++task->refs; }
void put_task_struct(task_struct* task) { assert(task->refs>1);--task->refs; }
#define msu_dynamic_get_task_struct get_task_struct
#define msu_dynamic_put_task_struct put_task_struct
void schedule_work(unsigned*) { ++scheduled; }
#define pr_err(...) ((void)0)

// EXACT_STRUCTS

struct { msu_publication publications[MSU_MANAGEMENT_SLOTS]; } management;
msu_publication* msu_publication_find(u64 id) { for(auto& p:management.publications)if(p.reserved&&p.id==id)return &p;return nullptr; }
bool lifetime_valid = true;
bool msu_publication_lifetime_valid(msu_publication*) { return lifetime_valid; }

// EXACT_FUNCTIONS

void setup(msu_publication& publication,u64 id,msu_private_return_owner* pool) {
    publication={};publication.id=id;publication.reserved=true;publication.ever_exposed=true;
    publication.enabled=true;publication.payload=0xffff000001000000ULL;
    publication.private_return_pool=pool;INIT_LIST_HEAD(&publication.private_return_owners);
    std::memset(pool,0,sizeof(*pool)*MSU_MANAGEMENT_SLOTS);
}
int main() {
    msu_private_return_owner pool[MSU_MANAGEMENT_SLOTS];
    auto& p=management.publications[0];
    for(auto& publication:management.publications)INIT_LIST_HEAD(&publication.private_return_owners);
    task_struct first,second;
    current=&first;
    setup(p,1,pool);
    unsigned checks=0;
    auto check=[&](bool ok,const char* name){++checks;if(!ok){std::cerr<<name<<"\n";std::abort();}};
    u64 tokens[65];
    for(unsigned i=0;i<65;++i)tokens[i]=msu_private_return_owner_acquire(1,0x28+i,0x30+i,0x1000+i*16);
    check(p.references==65&&p.private_return_count==65&&allocated==1,"64 pool plus 65th overflow retain real refs");
    for(unsigned i=0;i<64;++i)check(tokens[i]==(u64)(uintptr_t)&pool[i]&&pool[i].occupied,"pool allocation identity");
    check(!((msu_private_return_owner*)(uintptr_t)tokens[64])->pooled,"overflow uses dynamic record");
    check(first.refs==66,"task refs held through original");
    p.enabled=false;p.retire_requested=true;
    check(!msu_private_return_owner_acquire(1,0,0,0),"disable stops new admission");
    check(p.references==65&&released==0,"sleeping originals block release after disable");
    for(unsigned i=0;i<65;++i){
        check(msu_private_return_payload(tokens[i])==p.payload,"admitted payload survives disable");
        check(msu_private_return_caller_x28(tokens[i])==0x28+i,"caller x28 owned");
        check(msu_private_return_caller_lr(tokens[i])==0x30+i,"caller lr owned");
        msu_private_return_owner_release(tokens[i]);
    }
    check(!p.references&&!p.private_return_count&&released==1&&first.refs==1,"all owners returned after callbacks");
    check(scheduled>0,"last owner wakes retirement");
    for(auto& owner:pool)check(!owner.occupied,"all pool slots reusable");
    setup(p,2,pool);allocated=released=scheduled=0;
    for(unsigned i=0;i<64;++i)tokens[i]=msu_private_return_owner_acquire(2,i,i,0x1000);
    fail_allocation=true;
    check(!msu_private_return_owner_acquire(2,0,0,0),"overflow allocation failure rejects before admission");
    check(p.references==64&&p.private_return_count==64&&!p.enabled&&p.retire_requested,"failure retains old refs closes future calls");
    check(p.private_return_error==-ENOMEM&&p.private_return_failures==1,"ENOMEM retained");
    fail_allocation=false;
    for(unsigned i=0;i<64;++i)msu_private_return_owner_release(tokens[i]);
    check(!p.references&&first.refs==1&&allocations.empty(),"failure recovery releases every admitted ref");
    setup(p,3,pool);
    check(msu_publication_enter(3)==p.payload,"generic before admission");
    check(msu_publication_enter(3)==p.payload&&p.references==2,"nested generic before admission");
    current=&second;
    tokens[0]=msu_private_return_owner_acquire(3,0x77,0x88,0x1000);
    check(p.references==3&&second.refs==2,"different tasks hold independent refs");
    current=&first;first.flags=PF_EXITING;
    msu_private_return_owner_task_exit(&first);
    check(p.references==1&&p.private_return_count==1&&first.refs==1,"task exit cleans only its generic owners");
    check(p.private_return_error==-ESHUTDOWN&&!p.enabled&&p.retire_requested,"task exit records abandoned stack");
    current=&second;
    check(msu_private_return_payload(tokens[0])==p.payload,"other task callback remains live");
    msu_private_return_owner_release(tokens[0]);
    check(!p.references&&!p.private_return_count&&second.refs==1,"surviving task callback balances ref");
    first.flags=0;current=&first;setup(p,4,pool);
    check(msu_publication_enter(4)==p.payload,"generic enter again");
    auto* outer=container_of(p.private_return_owners.next,msu_private_return_owner,node);
    check(msu_publication_enter(4)==p.payload,"generic nested enter again");
    msu_publication_leave(4);
    check(p.references==1&&outer->occupied&&p.private_return_owners.next==&outer->node,"generic leave is task LIFO");
    msu_publication_leave(4);
    check(!p.references&&first.refs==1,"generic enter leave balance");
    msu_publication_leave(4);
    check(p.private_return_error==-EINVAL&&p.retire_requested,"unmatched leave produces real failure");
    check(allocations.empty(),"no dynamic owners leaked");
    std::cout<<"checks="<<checks<<" passed=1 kernelSchedulerSeams=1\n";
}
