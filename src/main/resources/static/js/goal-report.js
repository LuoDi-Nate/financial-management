(() => {
  const card = document.querySelector('#ai-report details');
  if (!card) return;
  function openLinkedReport() {
    if (location.hash === '#ai-report') card.open = true;
  }
  window.addEventListener('hashchange', openLinkedReport);
  openLinkedReport();
})();
