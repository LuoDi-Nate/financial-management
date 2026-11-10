(function(){
  'use strict';
  const editor=document.querySelector('[data-goal-estimate-editor]');
  if(!editor || editor.dataset.bound)return;editor.dataset.bound='true';
  const retirement=document.querySelector('.retirement-page');
  const mainForm=document.getElementById('retirement-form');
  const ownForm=document.getElementById('goal-estimate-form');
  const inputs=[...editor.querySelectorAll('[name^=estimate]')];
  const field=name=>inputs.find(el=>el.name===name&&(!['radio','checkbox'].includes(el.type)||el.checked));
  const values=()=>Object.fromEntries(['estimateSource','estimateMonthlyAmount','estimateWishMonth'].map(name=>[name,field(name)?.value||'']));
  let baseline=values(),timer,request,generation=0;
  const status=editor.querySelector('[data-estimate-status]'),dirty=editor.querySelector('[data-estimate-draft]');
  function paintDraft(){const changed=JSON.stringify(values())!==JSON.stringify(baseline);dirty.hidden=!changed;document.querySelectorAll('[data-estimate-overview-draft]').forEach(el=>el.hidden=!changed);}
  document.addEventListener('goal-estimate-rendered',paintDraft);
  function valid(){
    const v=values(),errors={};
    editor.querySelector('[data-estimate-manual]').hidden=v.estimateSource!=='MANUAL';
    if(v.estimateSource==='MANUAL'&&(!/^-?\d+(?:\.\d{1,2})?$/.test(v.estimateMonthlyAmount)||Math.abs(Number(v.estimateMonthlyAmount))>1e9))errors.estimateMonthlyAmount='请填写有效金额,最多2位小数,绝对值不超过10亿元';
    if(v.estimateWishMonth&&!/^\d{4}-(?:0[1-9]|1[0-2])$/.test(v.estimateWishMonth))errors.estimateWishMonth='请填写YYYY-MM或留空';
    editor.querySelectorAll('[data-estimate-error]').forEach(el=>{const name=el.dataset.estimateError;el.textContent=errors[name]||'';field(name)?.setAttribute('aria-invalid',String(Boolean(errors[name])));});
    const button=editor.querySelector('[data-estimate-save]');if(button)button.disabled=Object.keys(errors).length>0;
    return errors;
  }
  function body(){const data=new FormData(retirement&&mainForm?mainForm:ownForm);for(const [name,value]of Object.entries(values()))data.set(name,value);return data;}
  async function preview(){
    if(Object.keys(valid()).length)return;
    const current=++generation;if(request)request.abort();request=new AbortController();
    const url=retirement&&mainForm?mainForm.action+'/preview':ownForm.action+'/preview';
    try{
      const response=await fetch(url,{method:'POST',body:body(),signal:request.signal});
      if(!response.ok)throw Error('preview');
      const doc=new DOMParser().parseFromString(await response.text(),'text/html');
      const next=doc.querySelector('[data-goal-estimate-result]'),old=document.querySelector('[data-goal-estimate-result]');
      if(current!==generation)return;
      if(next&&old){const open=old.querySelector('details')?.open;old.innerHTML=next.innerHTML;if(open&&old.querySelector('details'))old.querySelector('details').open=true;}
      paintDraft();
    }catch(e){if(e.name!=='AbortError')status.textContent='时间试算暂未更新,请稍后重试。';}
  }
  inputs.forEach(el=>el.addEventListener('input',()=>{valid();paintDraft();status.textContent='';clearTimeout(timer);timer=setTimeout(preview,450);}));
  if(ownForm)ownForm.addEventListener('submit',async event=>{
    event.preventDefault();const errors=valid();if(Object.keys(errors).length){field(Object.keys(errors)[0]).focus();return;}
    clearTimeout(timer);++generation;if(request)request.abort();
    const button=editor.querySelector('[data-estimate-save]');button.disabled=true;
    try{
      const data=new FormData(ownForm);
      const response=await fetch(ownForm.action,{method:'POST',body:data,headers:{'X-Goal-Estimate':'true'}});
      if(!response.ok)throw Error('save');baseline=values();dirty.hidden=true;status.textContent='时间估算已保存。';await preview();
    }catch(e){status.textContent='时间估算未保存,请检查后重试。';}finally{valid();}
  });
  editor.querySelector('[data-estimate-reset]')?.addEventListener('click',()=>{inputs.forEach(el=>{if(el.type==='radio')el.checked=el.value===baseline[el.name];else el.value=baseline[el.name]||'';});dirty.hidden=true;valid();preview();});
  document.addEventListener('click',event=>{const link=event.target.closest('[data-estimate-edit]');if(!link)return;
    if(retirement){retirement.querySelector('[data-retire-tab=plan]').click();}requestAnimationFrame(()=>{editor.scrollIntoView({block:'start',behavior:'smooth'});});
  });
  window.goalEstimateData=values;
  if(mainForm)mainForm.addEventListener('submit',event=>{
    const errors=valid();if(Object.keys(errors).length&&editor.dataset.goalId===''){event.preventDefault();retirement.querySelector('[data-retire-tab=plan]').click();field(Object.keys(errors)[0]).focus();return;}
    mainForm.querySelectorAll('[data-estimate-plan-copy]').forEach(el=>el.remove());
    if(!Object.keys(errors).length&&JSON.stringify(values())!==JSON.stringify(baseline))for(const [name,value]of Object.entries(values())){const hidden=document.createElement('input');hidden.type='hidden';hidden.name=name;hidden.value=value;hidden.dataset.estimatePlanCopy='';mainForm.append(hidden);}
  });
  valid();
})();
