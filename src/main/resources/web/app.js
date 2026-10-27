'use strict';
let state, csrf = '', page = 'overview', offset = 0, logRows = [];
const $ = id => document.getElementById(id);
const titles = {overview:['대시보드','모든 연결이 올바른 곳에 도착하도록.'],routing:['서버 · 라우팅','안정적인 연결을 위한 유연한 규칙.'],logs:['플레이어 접속 기록','연결이 남긴 기록을 한눈에.'],settings:['설정','네트워크 운영을 내 방식대로.']};
const fieldGroups = [
 ['데이터베이스','자정 업데이트와 IP 조회에 사용하는 설정입니다.',[
  ['token','IPinfo 토큰','password','IPINFO_TOKEN 환경변수가 우선합니다. 빈 값은 기존 토큰을 유지합니다.'],
  ['database-url','다운로드 URL','text','{token}은 다운로드할 때 실제 토큰으로 바뀝니다.'],
  ['database-file','DB 파일 이름','text','플러그인 폴더 내 파일 이름. 변경 시 재시작이 필요합니다.'],
  ['database-timezone','업데이트 시간대','text','기본 Asia/Seoul. 매일 이 시간대의 00:00에 갱신합니다.'],
  ['connect-timeout-seconds','연결 제한 시간 (초)','number','1–300초'],['read-timeout-seconds','다운로드 제한 시간 (초)','number','1–3600초']]],
 ['라우팅 · 기록','상태 확인과 접속 기록에 사용하는 설정입니다.',[
  ['health-check-timeout-millis','TCP 상태 확인 제한 시간 (ms)','number','100–5000ms. 상태 확인 간격은 10초입니다.'],
  ['connection-log-retention-days','접속 기록 보관 기간 (일)','number','1–365일. 지난 기록은 다음 로그 기록 시 정리합니다.'],
  ['no-server-message','연결 거절 메시지','text','MiniMessage 형식. 모든 경로를 사용할 수 없을 때 표시합니다.'],
  ['log-lookups','접속 조회를 콘솔에도 출력','checkbox','파일 접속 기록은 이 설정과 관계없이 저장됩니다.'],
  ['fallback-enabled','Fallback 서버 사용','checkbox','일반 경로를 모두 사용할 수 없을 때만 사용합니다.']]],
 ['웹패널 · 보안','이 그룹의 변경은 프록시 재시작 후 적용됩니다.',[
  ['web-bind','바인드 주소','text','127.0.0.1 + Tailscale Serve 권장. 또는 서버에 할당된 Tailscale IP.'],
  ['web-port','포트','number','1–65535'],['web-password','관리자 비밀번호 변경','password','12자 이상. 빈 값은 기존 비밀번호 유지. ISPSENDER_WEB_PASSWORD 환경변수가 우선합니다.'],
  ['web-enabled','웹패널 활성화','checkbox','비활성화하면 재시작 후 이 패널에 접속할 수 없습니다.']]],
 ['이전 버전 호환 설정','아래 키는 보존되지만, 일일 갱신 정책이 우선하므로 더 이상 사용하지 않습니다.',[
  ['update-on-start','이전 시작 시 갱신 옵션','checkbox','날짜별 갱신 정책으로 대체되었습니다.'],['update-interval-minutes','이전 갱신 주기 (분)','number','자정 갱신으로 대체되었습니다.']]]
];
async function api(path, options = {}) {
 const response = await fetch(path,{...options,headers:{'Content-Type':'application/json','X-CSRF-Token':csrf,...options.headers}});
 const data = await response.json();
 if(!response.ok){if(response.status===401 && path!=='/api/login') showLogin(); throw new Error(data.error || '요청에 실패했습니다.');}
 return data;
}
function notify(text,error=false){$('notice').textContent=text;$('notice').classList.toggle('error',error);$('notice').hidden=false;}
function showLogin(){$('app').hidden=true;$('login').hidden=false;state=null;csrf='';}
function navigate(next){page=next;document.querySelectorAll('.page').forEach(el=>el.hidden=el.id!==page);document.querySelectorAll('.nav').forEach(el=>el.classList.toggle('active',el.dataset.page===page));$('page-title').textContent=titles[page][0];$('page-description').textContent=titles[page][1];if(page==='logs')loadLogs().catch(e=>notify(e.message,true));}
function poolText(value){return (Array.isArray(value)?value:value?[value]:[]).join('\n');}
function poolValue(value){return value.split('\n').map(v=>v.trim()).filter(Boolean);}
function textElement(tag,text,cls){let el=document.createElement(tag);el.textContent=text;if(cls)el.className=cls;return el;}
function addRule(type='asn',key='',servers=[]){
 let row=document.createElement('div');row.className='rule-row';
 let select=document.createElement('select');select.setAttribute('aria-label','규칙 종류');for(let [value,label] of [['asn','ASN'],['country','국가']]){let option=textElement('option',label);option.value=value;select.append(option);}select.value=type;
 let input=document.createElement('input');input.placeholder='AS4766 / KR';input.value=key;input.required=true;input.setAttribute('aria-label','ASN 또는 국가');
 let area=document.createElement('textarea');area.value=poolText(servers);area.placeholder='server1.example.com:25565\nserver2.example.com:25565';area.setAttribute('aria-label','서버 목록');area.required=true;
 let remove=textElement('button','×','remove-rule');remove.type='button';remove.setAttribute('aria-label','규칙 삭제');remove.onclick=()=>row.remove();row.append(select,input,area,remove);$('rules').append(row);
}
function renderConfig(){
 $('default-servers').value=poolText(state.config['default-server']);$('fallback-servers').value=poolText(state.config['fallback-servers']);$('rules').replaceChildren();
 for(let [type,key] of [['asn','asn-servers'],['country','country-servers']])for(let [rule,servers] of Object.entries(state.config[key]||{}))addRule(type,rule,servers);
 $('settings-fields').replaceChildren();
 for(let [title,description,fields] of fieldGroups){let card=document.createElement('article');card.className='card';card.append(textElement('h2',title),textElement('p',description));let grid=document.createElement('div');grid.className='settings-grid';
  for(let [key,label,type,help] of fields){let wrap=document.createElement('div');wrap.className='field'+(type==='checkbox'?' boolean':'');let lab=textElement('label',label);lab.htmlFor='field-'+key;let input=document.createElement('input');input.id='field-'+key;input.name=key;input.type=type;
   if(type==='checkbox')input.checked=!!state.config[key];else input.value=state.config[key]??'';
   if(type==='password'){input.autocomplete='new-password';input.placeholder='변경할 때만 입력';}if(type==='number')input.step='1';
   wrap.append(lab,input);if(type!=='checkbox')wrap.append(textElement('div',help,'field-help'));else input.title=help;grid.append(wrap);
  }card.append(grid);$('settings-fields').append(card);
 }
}
function renderRows(target,rows){
 target.replaceChildren();
 for(let log of rows){let tr=document.createElement('tr');let time=textElement('td',new Date(log.time).toLocaleString('ko-KR',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'}));
  let who=document.createElement('td');who.append(textElement('strong',log.player),textElement('small',log.ip));let region=textElement('td',[log.asn,log.country].filter(Boolean).join(' · ')||'—');
  let server=document.createElement('td');server.append(textElement('strong',log.server||'—'),textElement('small',log.rule||'사용 가능한 서버 없음'));
  let result=document.createElement('td');result.append(textElement('span',log.result==='transferred'?'전송':'거절','badge'+(log.result==='transferred'?'':' down')));tr.append(time,who,region,server,result);target.append(tr);
 }
 if(!rows.length){let row=document.createElement('tr'),cell=textElement('td','표시할 접속 기록이 없습니다.','empty');cell.colSpan=5;row.append(cell);target.append(row);}
}
function renderLive(){
 let alive=state.servers.filter(s=>s.alive).length;$('stat-servers').textContent=`${alive} / ${state.servers.length}`;$('stat-fallback').textContent=state.config['fallback-enabled']?'켜짐':'꺼짐';$('fallback-toggle').checked=state.config['fallback-enabled'];$('stat-db').textContent=state.databaseLoaded?'사용 중':'대기 중';$('stat-date').textContent=state.lastDownload?'마지막 다운로드 '+state.lastDownload:'다운로드 성공 기록 없음';$('next-download').textContent='다음 DB 업데이트 '+(state.nextDownload?new Date(state.nextDownload).toLocaleString('ko-KR'):'확인 중');$('restart').hidden=!state.restartRequired;
 $('server-list').replaceChildren();for(let server of state.servers){let row=document.createElement('div');row.className='server-item';let name=document.createElement('div');name.append(textElement('strong',server.server),textElement('small',server.checkedAt?'확인 '+new Date(server.checkedAt).toLocaleTimeString('ko-KR'):'첫 상태 확인 대기'));row.append(name,textElement('span',server.alive?`${server.latencyMillis} ms · 정상`:server.checkedAt?'응답 없음':'확인 대기','badge'+(server.alive?'':' down')));$('server-list').append(row);}if(!state.servers.length)$('server-list').append(textElement('div','서버 · 라우팅에서 서버를 추가하세요.','empty'));
 renderRows($('recent-rows'),state.recentLogs.slice(0,8));
}
async function refresh(config=false){const next=await api('/api/state');const first=!state;state=next;csrf=next.csrf;$('login').hidden=true;$('app').hidden=false;renderLive();if(config||first)renderConfig();}
async function save(values){const result=await api('/api/config',{method:'PUT',body:JSON.stringify(values)});await refresh(true);notify(result.restartRequired?'설정을 저장했습니다. 일부 변경은 재시작 후 적용됩니다.':'저장했습니다. 변경 사항이 적용되었습니다.');}
async function loadLogs(){logRows=await api(`/api/logs?date=${encodeURIComponent($('log-date').value)}&offset=${offset}`);filterLogs();$('log-page').textContent=`${offset/100+1} 페이지`;$('prev-logs').disabled=offset===0;$('next-logs').disabled=logRows.length<100;}
function filterLogs(){const q=$('log-search').value.toLowerCase();renderRows($('log-rows'),logRows.filter(r=>Object.values(r).some(v=>String(v).toLowerCase().includes(q))));}
function action(fn){return async event=>{event?.preventDefault();const button=event?.submitter;try{if(button)button.disabled=true;await fn(event);}catch(e){notify(e.message,true);}finally{if(button)button.disabled=false;}};}
$('login-form').onsubmit=async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;try{const result=await api('/api/login',{method:'POST',body:JSON.stringify({password:$('password').value})});csrf=result.csrf;$('password').value='';$('login-error').textContent='';await refresh(true);}catch(e){$('login-error').textContent=e.message;}finally{button.disabled=false;}};
$('logout').onclick=action(async()=>{await api('/api/logout',{method:'POST',body:'{}'});showLogin();});
$('refresh').onclick=action(()=>refresh());document.querySelectorAll('[data-page]').forEach(el=>el.onclick=()=>navigate(el.dataset.page));
$('add-rule').onclick=()=>addRule();
$('routing-form').onsubmit=action(async()=>{let asn={},country={};for(let row of $('rules').children){let [type,key,servers]=row.children;let map=type.value==='asn'?asn:country;let k=key.value.trim();let canonical=type.value==='asn'?k.toUpperCase().replace(/^AS/,''):k.toUpperCase();if(Object.keys(map).some(old=>(type.value==='asn'?old.toUpperCase().replace(/^AS/,''):old.toUpperCase())===canonical))throw new Error('중복된 규칙입니다: '+k);map[k]=poolValue(servers.value);}await save({'asn-servers':asn,'country-servers':country,'default-server':poolValue($('default-servers').value),'fallback-servers':poolValue($('fallback-servers').value)});});
$('settings-form').onsubmit=action(async()=>{let values={};for(let input of $('settings-fields').querySelectorAll('input'))values[input.name]=input.type==='checkbox'?input.checked:input.type==='number'?Number(input.value):input.value;await save(values);});
$('fallback-toggle').onchange=action(async()=>{try{await save({'fallback-enabled':$('fallback-toggle').checked});}catch(e){$('fallback-toggle').checked=state.config['fallback-enabled'];throw e;}});
const now=new Date();$('log-date').value=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
$('load-logs').onclick=action(async()=>{offset=0;await loadLogs();});$('prev-logs').onclick=action(async()=>{offset=Math.max(0,offset-100);await loadLogs();});$('next-logs').onclick=action(async()=>{offset+=100;await loadLogs();});$('log-search').oninput=filterLogs;
$('export').onclick=action(async()=>{const button=$('export');button.disabled=true;try{let rows=[],batch,index=0;do{batch=await api(`/api/logs?date=${encodeURIComponent($('log-date').value)}&offset=${index}`);rows.push(...batch);index+=100;}while(batch.length===100);let url=URL.createObjectURL(new Blob([JSON.stringify(rows,null,2)],{type:'application/json'}));let a=document.createElement('a');a.href=url;a.download=`ispsender-${$('log-date').value}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}finally{button.disabled=false;}});
setInterval(()=>{if(state && !document.hidden)refresh().catch(e=>{if(state)notify(e.message,true);});},10000);
refresh().catch(()=>showLogin());
