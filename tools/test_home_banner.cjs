// Dependency-free behavior tests with a small DOM fixture; not an Android/browser test.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/home-banner.js'), 'utf8');
class Element {
  constructor(tag, text='', attributes={}) { this.tag=tag; this.text=text; this.attrs={...attributes}; this.children=[]; }
  appendChild(child) { this.children.push(child); child.parentElement=this; return child; }
  get textContent() { return this.text + this.children.map(c=>c.textContent).join(''); }
  set textContent(value) { this.text=value; this.children=[]; }
  matches(selector) {
    return selector.split(',').some(s=>s.startsWith('[')
      ? (()=>{const [key,value]=s.slice(1,-1).split('='); return key in this.attrs && (!value || this.attrs[key]===value.replaceAll('"',''));})()
      : this.tag===s);
  }
  closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector); }
  querySelectorAll(selector) { return this.children.flatMap(c=>[...(c.matches(selector)?[c]:[]),...c.querySelectorAll(selector)]); }
  querySelector(selector) { return this.querySelectorAll(selector)[0]; }
  setAttribute(k,v) { this.attrs[k]=v; }
  removeAttribute(k) { delete this.attrs[k]; }
  getBoundingClientRect() { return {top:this.top || 0,height:100}; }
}
function fixture(route='/', origin='https://chatgpt.com') {
  const html=new Element('html'), head=html.appendChild(new Element('head')), body=html.appendChild(new Element('body'));
  const callbacks=[];
  const context={location:{origin,pathname:route},innerHeight:900,window:{addEventListener(){}},
    document:{head,body,documentElement:html,createElement:t=>new Element(t),querySelectorAll:s=>html.querySelectorAll(s)},
    requestAnimationFrame:f=>callbacks.push(f),MutationObserver:class {constructor(f){this.f=f;} observe(){}}};
  const flush=()=>{while(callbacks.length) callbacks.shift()();};
  return {body,context,flush,run(){vm.runInNewContext(source,context);flush();},refresh(){context.window.__gptNativeRefreshHomeBanner();flush();}};
}
function banner(parent, title='工作区有成员达到使用上限') {
  const box=parent.appendChild(new Element('div'));
  box.appendChild(new Element('h2',title));
  box.appendChild(new Element('p','开启自动充值，系统会自动补充额度，避免今后再次中断。'));
  box.appendChild(new Element('button','开启自动充值'));
  return box;
}
const hidden=e=>'data-gpt-native-quota-banner' in e.attrs;
let f=fixture(), box=banner(f.body); f.run(); assert(hidden(box)); assert(!hidden(f.body));
f.context.location.pathname='/c/example'; f.refresh(); assert(!hidden(box));
f.context.location.pathname='/'; f.refresh(); assert(hidden(box));
box.children[0].text='账户安全警告'; f.refresh(); assert(!hidden(box));
f=fixture('/c/example'); box=banner(f.body); f.run(); assert(!hidden(box));
f=fixture(); box=banner(f.body.appendChild(new Element('article'))); f.run(); assert(!hidden(box));
f=fixture(); box=banner(f.body.appendChild(new Element('div','',{'contenteditable':'true'}))); f.run(); assert(!hidden(box));
f=fixture(); box=banner(f.body,'其他重要通知'); f.run(); assert(!hidden(box));
f=fixture(); box=banner(f.body); box.top=700; f.run(); assert(!hidden(box));
f=fixture('/', 'https://example.com'); box=banner(f.body); f.run(); assert(!hidden(box));
f=fixture(); f.run(); box=banner(f.body); f.refresh(); assert(hidden(box));
console.log('PASS: home banner, route restoration, changed notice, transcript/editor exclusions, geometry and origin.');
