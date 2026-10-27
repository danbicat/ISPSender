// Runs before the stylesheet paints, including on the password screen.
(() => {
  const key = "ispsender-theme";
  const allowed = new Set(["system", "light", "dark"]);
  const system = window.matchMedia("(prefers-color-scheme: dark)");
  let preference = "system";
  try {
    const saved = localStorage.getItem(key);
    if (allowed.has(saved)) preference = saved;
  } catch (_) {
    /* Private browsers may deny storage; theme switching still works. */
  }
  function apply() {
    document.documentElement.dataset.theme =
      preference === "system"
        ? system.matches
          ? "dark"
          : "light"
        : preference;
    document.documentElement.dataset.themePreference = preference;
  }
  apply();
  system.addEventListener("change", () => {
    if (preference === "system") apply();
  });
  document.addEventListener("DOMContentLoaded", () => {
    const picker = document.getElementById("theme");
    picker.value = preference;
    picker.addEventListener("change", () => {
      preference = allowed.has(picker.value) ? picker.value : "system";
      try {
        localStorage.setItem(key, preference);
      } catch (_) {}
      apply();
    });
    window.addEventListener("storage", (event) => {
      if (event.key !== key) return;
      preference = allowed.has(event.newValue) ? event.newValue : "system";
      picker.value = preference;
      apply();
    });
  });
})();
