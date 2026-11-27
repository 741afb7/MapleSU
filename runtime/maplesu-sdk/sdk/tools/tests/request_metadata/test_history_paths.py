"""Run exact history path helper bodies; storage identity is covered separately."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[5]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--out',type=Path,required=True)
p.add_argument('--baseline-dir',type=Path)
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
rows=[];fragments=[]
provenance_paths=[Path(__file__).resolve(),ROOT/'runtime/maplesu-sdk/sdk/include/maplesu_request_private.h',ROOT/'runtime/ultra/authorization/request_protocol.h']
provenance={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in provenance_paths}
for short in ('idle_mem','hook_disable_flag'):
    name='module_base_kernel_'+short+'.cpp'
    source=(a.baseline_dir/('before-'+name)) if a.baseline_dir else ROOT/'runtime/maplesu-sdk/sdk/src/module_base'/name
    raw=source.read_bytes();text=raw.decode('utf-8-sig').replace('\r\n','\n')
    begin=text.index('static KModErr get_'+short+'_history_paths(')
    end=text.index('\n}\n',begin)+3
    fragments.append('namespace kernel_module::detail_'+short+'_history {\n'+text[begin:end]+'\n}\n')
    rows.append(dict(path=str(source),sha256=hashlib.sha256(raw).hexdigest(),helper=text[begin:end]))
(out/'actual_history_paths.inl').write_text('\n'.join(fragments),encoding='utf8')
code=r'''#include "maplesu_request_private.h"
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <system_error>
static uint64_t auth=4;
static std::string directory;
static KModErr context_error=KModErr::OK;
namespace kernel_module { uint64_t maplesu_request_auth_state() {return auth;} }
namespace maplesu::storage {
KModErr current_kernel_directory(std::string& out) {
    if(context_error!=KModErr::OK)return context_error;
    out=directory; return KModErr::OK;
}
}
#include "actual_history_paths.inl"
int main() {
    unsigned checks=0,failures=0;
    auto check=[&](bool value){++checks;if(!value)++failures;};
    using Fn=KModErr(*)(std::string&,std::string&,std::string&);
    const Fn helpers[]={kernel_module::detail_idle_mem_history::get_idle_mem_history_paths,
        kernel_module::detail_hook_disable_flag_history::get_hook_disable_flag_history_paths};
    std::filesystem::create_directory("existing"); std::ofstream("ordinary-file")<<"fixture";
    for(auto helper:helpers) {
        for(uint64_t mode : {UINT64_C(4),UINT64_C(1),UINT64_C(3)}) {
            auth=mode;
            for(const char* path : {"existing/","missing/","ordinary-file/"}) {
                directory=path;std::string start,next,end;
                check(helper(start,next,end)==KModErr::OK);
                const std::string expected=(mode==4 && directory!="existing/")?"/dev/":directory;
                check(start.rfind(expected,0)==0&&next.rfind(expected,0)==0&&end.rfind(expected,0)==0);
            }
            context_error=KModErr::ERR_MODULE_ROOT_KEY;
            std::string start="start",next="next",end="end";
            check(helper(start,next,end)==context_error&&start=="start"&&next=="next"&&end=="end");
            context_error=KModErr::OK;
        }
    }
    std::printf("history-paths: %u checks, %u failures; exact helper bodies, context seam\n",checks,failures);
    return failures?1:0;
}
'''
(out/'test.cpp').write_text(code,encoding='utf8')
compiler=Path('C:/msys64/ucrt64/bin/g++.exe')
env=dict(os.environ);env['PATH']=str(compiler.parent)+os.pathsep+env.get('PATH','');env['TEMP']=env['TMP']=str(out)
commands=[]
for label,argv in [('compile',[str(compiler),'-std=c++20','-I'+str(ROOT/'runtime/maplesu-sdk/sdk/include'),'-I'+str(ROOT/'runtime/maplesu-sdk/include'),str(out/'test.cpp'),'-o',str(out/'history-test.exe')]),('cases',[str(out/'history-test.exe')])]:
    done=subprocess.run(argv,cwd=out,env=env,capture_output=True,text=True,encoding='utf8',errors='replace')
    (out/(label+'.log')).write_text(done.stdout+done.stderr,encoding='utf8')
    print(done.stdout+done.stderr,end='');commands.append(dict(label=label,argv=argv,exit=done.returncode))
    if done.returncode:break
stable=all(hashlib.sha256(Path(row['path']).read_bytes()).hexdigest()==row['sha256'] for row in rows) and all(hashlib.sha256(Path(name).read_bytes()).hexdigest()==value for name,value in provenance.items())
report=dict(passed=stable and len(commands)==2 and all(c['exit']==0 for c in commands),sources=rows,provenance=provenance,inputsStable=stable,commands=commands,scope='Exact production path helper bodies only; identity context seam; no full history allocation or device effect.')
(out/'receipt.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
raise SystemExit(0 if report['passed'] else 1)
