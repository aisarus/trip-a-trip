(()=>{
'use strict';
if(window.__tatMqttResilient||!window.mqtt?.connect)return;
window.__tatMqttResilient=true;
const originalConnect=window.mqtt.connect.bind(window.mqtt);
const STORE='tat-mqtt-outbox-v1';
function load(){try{const x=JSON.parse(localStorage.getItem(STORE)||'[]');return Array.isArray(x)?x:[]}catch(e){return[]}}
function save(q){try{localStorage.setItem(STORE,JSON.stringify(q))}catch(e){try{localStorage.setItem(STORE,JSON.stringify(q.slice(-8)))}catch(_){}}}
function enqueue(item){let q=load();if(item.opts?.retain){const i=q.findIndex(x=>x.topic===item.topic);if(i>=0)q.splice(i,1)}q.push(item);if(q.length>24)q=q.slice(-24);save(q)}
function markQueued(){const el=document.getElementById('brokerText');if(el&&el.textContent!=='Онлайн')el.textContent='Офлайн · отправлю позже'}
window.mqtt.connect=function(...args){
  const raw=originalConnect(...args);let proxy;
  function flush(){if(!raw.connected)return;let q=load();if(!q.length)return;const batch=[...q];let pending=batch.length;batch.forEach(item=>{try{raw.publish(item.topic,item.message,item.opts||{},err=>{pending--;if(!err){const cur=load();const idx=cur.findIndex(x=>x.id===item.id);if(idx>=0){cur.splice(idx,1);save(cur)}}if(pending===0){const el=document.getElementById('brokerText');if(el&&raw.connected)el.textContent='Онлайн'}})}catch(e){pending--}})}
  raw.on('connect',()=>setTimeout(flush,150));
  proxy=new Proxy(raw,{
    get(target,prop,receiver){
      if(prop==='connected')return true;
      if(prop==='publish')return function(topic,message,opts,cb){
        if(typeof opts==='function'){cb=opts;opts={}}
        opts=opts||{};
        if(raw.connected){return raw.publish(topic,message,opts,cb)}
        const item={id:(crypto.randomUUID?.()||Date.now().toString(36)+Math.random().toString(36).slice(2)),topic,message:String(message),opts:{qos:opts.qos??0,retain:!!opts.retain},ts:Date.now()};
        enqueue(item);markQueued();
        try{raw.reconnect?.()}catch(e){}
        if(typeof cb==='function')setTimeout(()=>cb(null),0);
        return proxy
      };
      const v=Reflect.get(target,prop,target);return typeof v==='function'?v.bind(target):v
    }
  });
  return proxy
};
})();
