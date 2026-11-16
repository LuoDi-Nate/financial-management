(function () {
  var host = document.getElementById('llm-catalog');
  if (!host) return;
  var catalog;
  try { catalog = JSON.parse(host.dataset.catalog); } catch (e) { return; }
  var form = document.getElementById('llm-models');
  var pattern = new RegExp(form.dataset.modelPattern);
  var max = Number(form.dataset.modelMax);
  var groups = [];
  function error(input, message) {
    var container = input.closest('[data-llm-triple]') || input.parentElement;
    container.querySelector('[data-llm-error]').textContent = message;
    input.setAttribute('aria-invalid', message ? 'true' : 'false');
  }
  function initTriple(prefix, modality, allowNone) {
    var platform = document.getElementById(prefix + 'Platform');
    var family = document.getElementById(prefix + 'Family');
    var model = document.getElementById(prefix + 'ModelId');
    var list = document.getElementById(prefix + 'Models');
    var hint = document.getElementById(prefix + 'Hint');
    function platformDef() { return catalog.find(function (p) { return p.code === platform.value; }); }
    function families() { var p = platformDef(); return p ? p.families.filter(function (f) { return f.modality === modality; }) : []; }
    function current() { return families().find(function (f) { return f.code === family.value; }); }
    function renderModels() {
      var f = current(); list.replaceChildren();
      model.disabled = !f;
      if (!f) { model.value = ''; hint.textContent = ''; return; }
      f.models.forEach(function (m) { var o = document.createElement('option'); o.value = m.id; o.label = m.label; list.appendChild(o); });
      model.placeholder = f.mustFillModel ? '必填 · 复制完整型号' : '留空 = 自动';
      hint.textContent = f.mustFillModel ? '此系列没有默认型号,请从本平台复制完整型号。'
        : platformDef().modelRotation && modality === 'TEXT' ? '自动沿用百炼型号池轮询;手填时只用该型号。'
        : '留空 = 用推荐型号 ' + f.defaultModel + ';也可手填。';
      if (modality === 'VISION') hint.textContent += ' 所选型号需支持图片输入。';
      else hint.textContent += ' 本地 Agent 的型号需支持工具调用。';
    }
    function renderFamilies(keep) {
      var want = keep ? family.dataset.current || family.value : '';
      family.replaceChildren();
      families().forEach(function (f) { family.appendChild(new Option(f.label, f.code, false, f.code === want)); });
      family.disabled = !family.options.length; renderModels();
    }
    platform.addEventListener('change', function () { model.value = ''; renderFamilies(false); error(model, ''); });
    family.addEventListener('change', function () { model.value = ''; renderModels(); error(model, ''); });
    model.addEventListener('input', function () { error(model, ''); });
    renderFamilies(true);
    var group = {prefix: prefix, platform: platform, family: family, model: model, current: current,
      validate: function () {
        error(model, '');
        if (allowNone && !platform.value) return true;
        if (!platform.value || !platform.selectedOptions[0] || platform.selectedOptions[0].disabled) { error(model, '先为此平台保存密钥,再选择模型'); return false; }
        var f = current(), raw = model.value.trim(), auto = !raw || platform.value !== 'custom' && raw.toLowerCase() === 'auto';
        if (!f || auto && f.mustFillModel) { error(model, '此系列没有默认型号,请填写完整型号'); return false; }
        if (!auto && (raw.length > max || !pattern.test(raw))) { error(model, '型号格式不合法,请保留前缀和大小写,不填网址或密钥'); return false; }
        return true;
      }, strategy: function () {
        var f = current(), raw = model.value.trim(), auto = !raw || platform.value !== 'custom' && raw.toLowerCase() === 'auto';
        var p = platformDef(); return [platform.value, family.value, auto ? p && p.modelRotation && modality === 'TEXT' ? '@rotation' : f && f.defaultModel : raw].join('|');
      }};
    groups.push(group);
  }
  initTriple('primary', 'TEXT', false); initTriple('backup', 'TEXT', true); initTriple('vision', 'VISION', false);
  var vision = document.getElementById('visionEnabled');
  function showVision() { form.querySelector('[data-llm-vision]').hidden = !vision.checked; }
  vision.addEventListener('change', showVision); showVision();
  form.noValidate = true;
  form.addEventListener('submit', function (event) {
    var active = vision.checked ? groups : groups.slice(0, 2);
    var first = null;
    active.forEach(function (g) { if (!g.validate() && !first) first = g.model; });
    if (!first && groups[1].platform.value && groups[0].strategy() === groups[1].strategy()) {
      error(groups[1].model, '备选与主选完全相同,请更换或清空备选'); first = groups[1].model;
    }
    form.querySelectorAll('input[type=number]').forEach(function (input) {
      error(input, ''); if (!input.checkValidity()) { error(input, '请填写范围内的数值'); if (!first) first = input; }
    });
    if (first) { event.preventDefault(); first.focus(); first.scrollIntoView({block:'center'}); }
  });
  document.querySelectorAll('[data-llm-key]').forEach(function (keyForm) {
    keyForm.addEventListener('submit', function (event) {
      var input = keyForm.elements.apiKey;
      if (!input.value.trim()) { event.preventDefault(); keyForm.querySelector('[data-llm-error]').textContent = '没有输入新密钥,原密钥未改动'; input.focus(); }
    });
  });
  var own = document.getElementById('llm-custom-service');
  if (own) {
    var ownUrl = own.elements.baseUrl, ownAuth = own.elements.authMode, ownKey = own.elements.apiKey;
    function customError(name, message) {
      own.querySelector('[data-custom-error="' + name + '"]').textContent = message;
      own.elements[name].setAttribute('aria-invalid', message ? 'true' : 'false');
    }
    function canonical(raw) { try { return new URL(raw.trim()).href.replace(/\/+$/, ''); } catch (e) { return ''; } }
    function updateOwn() {
      own.querySelector('[data-custom-key]').hidden = ownAuth.value === 'NONE';
      own.querySelector('[data-custom-endpoint]').textContent = canonical(ownUrl.value) ? canonical(ownUrl.value) + '/chat/completions' : '—';
    }
    ownAuth.addEventListener('change', updateOwn); ownUrl.addEventListener('input', updateOwn); updateOwn();
    own.noValidate = true;
    own.addEventListener('submit', function (event) {
      var first = null;
      ['displayName','baseUrl','apiKey','allowLocalHttp'].forEach(function (name) { customError(name, ''); });
      function bad(name, message) { customError(name, message); if (!first) first = own.elements[name]; }
      var url;
      try { url = new URL(ownUrl.value.trim()); } catch (e) { bad('baseUrl', '请填写绝对HTTP(S)基础地址'); }
      if (url) {
        if (!/^https?:$/.test(url.protocol) || url.username || url.password || url.search || url.hash || /\/(chat\/completions|responses)\/?$/i.test(url.pathname))
          bad('baseUrl', '填写API前缀,不含认证信息、查询参数或完整调用路径');
        if (url.protocol === 'http:' && !own.elements.allowLocalHttp.checked) bad('allowLocalHttp', 'HTTP或内网服务需要明确许可');
      }
      if (ownAuth.value === 'BEARER' && !ownKey.value.trim() && (own.dataset.ready !== 'true' || own.dataset.savedAuth !== 'BEARER' || canonical(ownUrl.value) !== canonical(own.dataset.savedUrl)))
        bad('apiKey', '首次配置或更改地址/认证后,请重新输入此服务的密钥');
      if (own.elements.displayName.value.length > 50) bad('displayName', '名称最长50字');
      if (first) { event.preventDefault(); first.focus(); first.scrollIntoView({block:'center'}); }
    });
  }
  document.querySelectorAll('[data-llm-key], #llm-custom-service, #llm-models, [data-llm-probe]').forEach(function (target) {
    target.addEventListener('submit', function (event) {
      if (event.defaultPrevented) return;
      if (target.dataset.submitting) { event.preventDefault(); return; }
      target.dataset.submitting = 'true';
      document.querySelectorAll('#llm button[type=submit]').forEach(function (button) { button.disabled = true; });
    });
  });
  if (window.LensSelect) window.LensSelect.enhanceAll();
})();
