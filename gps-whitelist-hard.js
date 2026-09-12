(()=>{
'use strict';
if(window.__tatHardGpsWhitelist)return;
window.__tatHardGpsWhitelist=true;

const ALLOWED=new Map([
  ['сеня','Сеня'],['senya','Сеня'],
  ['яна','Яна'],['yana','Яна']
]);
const normalize=v=>{try{return String(v??'').trim().normalize('NFKC').toLowerCase()}catch(e){return String(v??'').trim().toLowerCase()}};
const canonical=v=>ALLOWED.get(normalize(v))||null;
const nativeParse=JSON.parse.bind(JSON);

JSON.parse=function(text,reviver){
  const value=nativeParse(text,reviver);
  if(value&&typeof value==='object'&&!Array.isArray(value)&&value.pos&&value.id&&typeof value.name==='string'){
    const name=canonical(value.name);
    if(!name)return null;
    if(value.name!==name)value.name=name;
  }
  return value;
};

window.TripATripHardGpsWhitelist={canonical,isAllowed:name=>!!canonical(name)};
})();
