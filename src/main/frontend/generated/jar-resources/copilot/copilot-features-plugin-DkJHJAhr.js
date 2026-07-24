import { a1 as l, u as s, E as u, e as f, x as o, D as v, F as w, ai as T, G as y, M as I, aj as q, ak as A, r as x, p as h } from "./copilot-CdD0jRv0.js";
import { B as C } from "./base-panel-DqYmxkT9.js";
const n = (a) => s.userInfo?.copilotExperimentalFeatureFlag === !0 && l.isExperimentalFeatureEnabled(a), F = {
  id: "theme-from-image",
  name: "Theme from Image",
  description: "Generate a custom theme based on an image you provide.",
  enabled: () => n(F),
  available: () => s.appTheme === "lumo",
  requiresReload: !1
}, R = {
  id: "ai-docs-assistant",
  name: "AI Docs Assistant",
  description: "AI-powered Vaadin documentation assistant.",
  enabled: () => n(R),
  available: () => !0,
  requiresReload: !1
}, $ = {
  id: "testbench-test-recorder",
  name: "TestBench Test Recorder",
  description: "Record user interactions to generate end-to-end Vaadin TestBench tests automatically.",
  enabled: () => n($),
  available: () => !0,
  requiresReload: !0
}, E = {
  id: "i18n",
  name: "Internationalization",
  description: "Edit and manage translations for your application.",
  enabled: () => n(E),
  available: () => !0,
  requiresReload: !0
}, O = [
  F,
  R,
  $,
  E
];
var M = Object.defineProperty, S = Object.getOwnPropertyDescriptor, d = (a, e, t, r) => {
  for (var i = r > 1 ? void 0 : r ? S(e, t) : e, c = a.length - 1, p; c >= 0; c--)
    (p = a[c]) && (i = (r ? p(e, t, i) : p(i)) || i);
  return r && i && M(e, t, i), i;
};
const g = window.Vaadin.devTools;
let m = class extends C {
  constructor() {
    super(...arguments), this.toggledFeaturesThatAreRequiresServerRestart = [];
  }
  connectedCallback() {
    super.connectedCallback(), this.classList.add("contents");
  }
  render() {
    const a = s.userInfo?.copilotExperimentalFeatureFlag;
    return o`
      <div class="flex flex-col gap-6 px-4 py-0.5">
        <div class="border-dashed flex flex-col divide-y">
          ${s.featureFlags.slice().sort((e, t) => e.title.localeCompare(t.title)).map(
      (e) => o`
                <div class="flex gap-2 justify-between py-3.5">
                  <div class="flex flex-col">
                    <label id="${e.id}-label">${e.title}</label>
                    <a
                      class="flex gap-0.5 text-xs"
                      href="${e.moreInfoLink}"
                      id="${e.id}-desc"
                      target="_blank"
                      rel="noopener noreferrer"
                      >More info<vaadin-icon class="icon-sm" .svg="${f.arrowOutward}"></vaadin-icon
                    ></a>
                  </div>
                  <copilot-toggle-button
                    accessible-name-ref="${e.id}-label"
                    accessible-desc-ref="${e.id}-desc"
                    ?checked=${e.enabled}
                    @on-change=${(t) => this.toggleFeatureFlag(t, e)}>
                  </copilot-toggle-button>
                </div>
              `
    )}
        </div>
        <div class="flex flex-col gap-1">
          ${a ? o`<h3 class="font-semibold my-0 text-sm">Copilot Experimental Features</h3>
                <div class="border-dashed flex flex-col divide-y">
                  ${O.filter((e) => e.available()).slice().sort((e, t) => e.name.localeCompare(t.name)).map(
      (e) => o`
                        <div class="flex gap-2 justify-between py-3.5">
                          <div class="flex flex-col">
                            <label id="${e.id}-label">${e.description}</label>
                          </div>
                          <copilot-toggle-button
                            accessible-name-ref="${e.id}-label"
                            ?checked=${l.isExperimentalFeatureEnabled(e)}
                            @on-change=${(t) => this.toggleExperimentalFeatureFlag(t, e)}>
                          </copilot-toggle-button>
                        </div>
                      `
    )}
                </div>` : u}
        </div>
      </div>
    `;
  }
  toggleFeatureFlag(a, e) {
    const t = a.target.checked;
    v("use-feature", { source: "toggle", enabled: t, id: e.id }), g.frontendConnection ? (g.frontendConnection.send("setFeature", { featureId: e.id, enabled: t }), e.requiresServerRestart && s.toggleServerRequiringFeatureFlag(e), w({
      type: y.INFORMATION,
      message: `“${e.title}” ${t ? "enabled" : "disabled"}`,
      details: e.requiresServerRestart ? T() : void 0,
      dismissId: `feature${e.id}${t ? "Enabled" : "Disabled"}`
    }), e.id === "copilotExperimentalFeatures" && s.userInfo && s.setUserInfo({ ...s.userInfo, copilotExperimentalFeatureFlag: t })) : g.log("error", `Unable to toggle feature ${e.title}: No server connection available`);
  }
  toggleExperimentalFeatureFlag(a, e) {
    const t = a.target.checked;
    v("use-experimental-feature", { source: "toggle", enabled: t, id: e.id });
    const r = l.isExperimentalFeatureEnabled(e);
    l.setExperimentalFeatureEnabled(e, t), e.requiresReload && t && !r && window.location.reload();
  }
};
d([
  x()
], m.prototype, "toggledFeaturesThatAreRequiresServerRestart", 2);
m = d([
  h("copilot-features-panel")
], m);
let b = class extends I {
  constructor() {
    super(...arguments), this.serverRestarting = !1;
  }
  createRenderRoot() {
    return this;
  }
  render() {
    if (s.serverRestartRequiringToggledFeatureFlags.length === 0)
      return u;
    if (!q())
      return u;
    const a = this.serverRestarting ? "Restarting..." : "Click to restart server";
    return o`
      <button
        ?disabled="${this.serverRestarting}"
        id="restart-server-btn"
        class="icon ${this.serverRestarting ? "" : "fade-in-out"}"
        @click=${() => {
      this.serverRestarting = !0, A();
    }}>
        <span>${f.refresh}</span>
      </button>
      <vaadin-tooltip for="restart-server-btn" text=${a}></vaadin-tooltip>
    `;
  }
};
d([
  x()
], b.prototype, "serverRestarting", 2);
b = d([
  h("copilot-features-actions")
], b);
const k = {
  header: "Features",
  tag: "copilot-features-panel",
  helpUrl: "https://vaadin.com/docs/latest/flow/configuration/feature-flags",
  actionsTag: "copilot-features-actions",
  toolbarOptions: {
    allowedModesWithOrder: {
      common: 0
    },
    iconKey: "listAlt"
  }
}, P = {
  init(a) {
    a.addPanel(k);
  }
};
window.Vaadin.copilot.plugins.push(P);
export {
  b as CopilotFeaturesActions,
  m as CopilotFeaturesPanel
};
