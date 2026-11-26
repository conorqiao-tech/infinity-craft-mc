if ("serviceWorker" in navigator && window.isSecureContext) {
  // Browsing and playback also work when installation or caching is unavailable.
  navigator.serviceWorker.register("/guide-worker.js").catch(() => {});
}
