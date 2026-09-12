(()=>{
'use strict';
if(window.__tatIdentityGuard)return;
window.__tatIdentityGuard=true;

const LEGACY_PURGE_BEFORE=Date.parse('2026-09-12T17:42:00Z');
const CANON=new Map([
  ['сеня','Сеня'],
  ['senya','Сеня'],
  ['яна','Яна'],
  ['yana','Яна']
]);
const MEMBER_RE=/^trip-a-trip\/v1\/[^/]+\/member\/[^/]+$/;
const BROKER='wss://broker.emqx.io:8084/mqtt';

function normalizeName(name){
  try{return String(name??'').trim().normalize('NFKC').toLowerCase()}catch(e){return String(name??'').trim().toLowerCase()}
}
function canonicalName(name){return CANON.get(normalizeName(name))||null}
function isAllowedName(name){return !!canonicalName(name)}
function isMemberTopic(topic){return MEMBER_RE.test(String(topic||''))}
function decodePayload(message){
  try{
    if(message==null)return null;
    let text;
    if(typeof message==='string')text=message;
    else if(typeof Buffer!=='undefined'&&Buffer.isBuffer?.(message))text=message.toString('utf8');
    else if(message instanceof Uint8Array)text=new TextDecoder().decode(message);
    else text=String(message);
    if(!text)return null;
    return JSON.parse(text);
  }catch(e){return null}
}
function shouldPurgeMember(payload){
  const canonical=canonicalName(payload?.name);
  if(!canonical)return true;
  if(canonical!=='Сеня'&&Number(payload?.ts||0)>0&&Number(payload.ts)<LEGACY_PURGE_BEFORE)return true;
  return false;
}
function roomFromUrl(){return new URLSearchParams(location.search).get('room')||''}
function currentEnteredName(){
  const input=document.getElementById('nameInput');
  if(input?.value?.trim())return input.value.trim();
  const qs=new URLSearchParams(location.search),q=qs.get('name');
  if(q?.trim())return q.trim();
  const room=qs.get('room');
  return room?(localStorage.getItem(`tat-name-${room}`)||''):'';
}
function showToast(text){
  const toast=document.getElementById('toast');
  if(toast){toast.textContent=text;toast.classList.add('show');clearTimeout(showToast.t);showToast.t=setTimeout(()=>toast.classList.remove('show'),2400)}
  else console.warn('[Trip-a-Trip]',text);
}
function showBlocked(){showToast('GPS доступен только для Сеня и Яна')}
function clearLocalTrail(room,id){
  try{if(room&&id)localStorage.removeItem(`tat-trail-${room}-${id}`)}catch(e){}
}
function hardenStoredIdentity(){
  try{
    const qs=new URLSearchParams(location.search),room=qs.get('room')||'',qName=qs.get('name')||'',storedName=room?(localStorage.getItem(`tat-name-${room}`)||''):'',name=qName||storedName;
    const canonical=canonicalName(name);
    if(canonical){
      if(room)localStorage.setItem(`tat-name-${room}`,canonical);
      if(qName&&qName!==canonical){qs.set('name',canonical);history.replaceState(null,'',location.pathname+'?'+qs.toString()+location.hash)}
      return;
    }
    if(name){
      if(room&&localStorage.getItem(`tat-role-${room}`)==='travel')localStorage.setItem(`tat-role-${room}`,'watch');
      if(qs.get('role')==='travel'){
        qs.set('role','watch');
        history.replaceState(null,'',location.pathname+'?'+qs.toString()+location.hash);
      }
      if(room){
        const deviceId=localStorage.getItem(`tat-device-${room}`);
        clearLocalTrail(room,deviceId);
      }
    }
  }catch(e){}
}
function cleanQueuedMemberPublishes(){
  try{
    const key='tat-mqtt-outbox-v1',raw=localStorage.getItem(key);
    if(!raw)return;
    const q=JSON.parse(raw);
    if(!Array.isArray(q))return;
    const clean=q.filter(item=>{
      if(!isMemberTopic(item?.topic))return true;
      const p=decodePayload(item?.message);
      return p&&!shouldPurgeMember(p);
    });
    if(clean.length!==q.length)localStorage.setItem(key,JSON.stringify(clean));
  }catch(e){}
}
function switchThisClientToWatch(){
  try{
    const room=roomFromUrl(),qs=new URLSearchParams(location.search);
    if(room)localStorage.setItem(`tat-role-${room}`,'watch');
    qs.set('role','watch');
    history.replaceState(null,'',location.pathname+'?'+qs.toString()+location.hash);
  }catch(e){}
}
function stopGpsFromClient(){
  const name=canonicalName(currentEnteredName());
  const room=roomFromUrl();
  if(!name){showBlocked();return}
  if(!room){showToast('Не найден room-key');return}
  const finish=()=>{
    switchThisClientToWatch();
    showToast(`GPS ${name} остановлен`);
    setTimeout(()=>location.reload(),550);
  };
  if(!window.mqtt?.connect){finish();return}
  let settled=false,client=null;
  const done=()=>{if(settled)return;settled=true;try{client?.end?.(true)}catch(e){}finish()};
  const timeout=setTimeout(done,2200);
  try{
    client=window.mqtt.connect(BROKER,{clientId:'tat_stop_'+Math.random().toString(16).slice(2),clean:true,reconnectPeriod:0,connectTimeout:1600,keepalive:10});
    client.on('connect',()=>{
      const topic=`trip-a-trip/v2/${room}/control/stop`;
      const payload=JSON.stringify({action:'stop-gps',name,ts:Date.now()});
      client.publish(topic,payload,{qos:1,retain:false},()=>{clearTimeout(timeout);done()});
    });
    client.on('error',()=>{clearTimeout(timeout);done()});
  }catch(e){clearTimeout(timeout);done()}
}
function installStopButton(){
  if(document.getElementById('stopGpsBtn'))return;
  const quick=document.getElementById('quickRow');
  if(!quick)return;
  const btn=document.createElement('button');
  btn.id='stopGpsBtn';
  btn.className='quick danger';
  btn.type='button';
  btn.style.cssText='width:100%;margin-top:8px;';
  btn.innerHTML='⏹<span>Остановить GPS</span>';
  btn.addEventListener('click',stopGpsFromClient);
  quick.insertAdjacentElement('afterend',btn);
}

window.TripATripIdentityGuard={canonicalName,isAllowedName,stopGpsFromClient};
hardenStoredIdentity();
cleanQueuedMemberPublishes();
installStopButton();

document.addEventListener('click',e=>{
  const button=e.target?.closest?.('button');
  if(!button||!['joinTravel','joinShare','trackBtn'].includes(button.id))return;
  if(isAllowedName(currentEnteredName()))return;
  e.preventDefault();
  e.stopImmediatePropagation();
  const room=roomFromUrl();
  try{if(room)localStorage.setItem(`tat-role-${room}`,'watch')}catch(_){}
  showBlocked();
},true);

if(!window.mqtt?.connect)return;
const originalConnect=window.mqtt.connect.bind(window.mqtt);
window.mqtt.connect=function(...args){
  const client=originalConnect(...args);
  return new Proxy(client,{
    get(target,prop,receiver){
      if(prop==='publish')return function(topic,message,opts,cb){
        if(isMemberTopic(topic)){
          const p=decodePayload(message);
          if(!p||!isAllowedName(p.name)){
            showBlocked();
            if(typeof cb==='function')queueMicrotask(()=>cb(new Error('Trip-a-Trip GPS identity blocked')));
            return receiver;
          }
          const canonical=canonicalName(p.name);
          if(canonical&&p.name!==canonical){
            p.name=canonical;
            message=JSON.stringify(p);
          }
        }
        return target.publish(topic,message,opts,cb);
      };
      if(prop==='on')return function(event,handler){
        if(event!=='message'||typeof handler!=='function')return target.on(event,handler);
        return target.on('message',(topic,message,...rest)=>{
          if(isMemberTopic(topic)){
            if(message==null||message.length===0)return;
            const p=decodePayload(message);
            if(!p||shouldPurgeMember(p)){
              const parts=String(topic).split('/');
              clearLocalTrail(parts[2],parts[4]);
              try{target.publish(topic,'',{qos:0,retain:true})}catch(e){}
              return;
            }
          }
          handler(topic,message,...rest);
        });
      };
      const value=Reflect.get(target,prop,target);
      return typeof value==='function'?value.bind(target):value;
    }
  });
};
})();