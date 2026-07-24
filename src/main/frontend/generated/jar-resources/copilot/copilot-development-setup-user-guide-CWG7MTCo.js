import { ap as A, N as $, q as b, R as l, u as d, S as x, c as y, x as n, e as r, ac as I, aq as S, E as p, T as J, f as H, h as D, P as E, M, ar as R, as as V, r as m, p as k } from "./copilot-CdD0jRv0.js";
import { B as O } from "./base-panel-DqYmxkT9.js";
var U = Object.defineProperty, _ = Object.getOwnPropertyDescriptor, g = (e, t, a, s) => {
  for (var i = s > 1 ? void 0 : s ? _(t, a) : t, o = e.length - 1, u; o >= 0; o--)
    (u = e[o]) && (i = (s ? u(t, a, i) : u(i)) || i);
  return s && i && U(t, a, i), i;
};
const C = "https://github.com/JetBrains/JetBrainsRuntime/releases";
function N(e, t) {
  if (!t)
    return !0;
  const [a, s, i] = t.split(".").map((h) => Number.parseInt(h)), [o, u, w] = e.split(".").map((h) => Number.parseInt(h));
  if (a < o)
    return !0;
  if (a === o) {
    if (s < u)
      return !0;
    if (s === u)
      return i < w;
  }
  return !1;
}
const P = "Download complete";
let c = class extends O {
  constructor() {
    super(), this.javaPluginSectionOpened = !1, this.hotswapSectionOpened = !1, this.hotswapTab = "hotswapagent", this.downloadStatusMessages = [], this.downloadProgress = 0, this.onDownloadStatusUpdate = this.downloadStatusUpdate.bind(this), this.handleESC = (e) => {
      $().appInteractable || e.key === "Escape" && b.openPanel(v.tag);
    }, this.reaction(
      () => [l.jdkInfo, d.idePluginState],
      () => {
        d.idePluginState && (!d.idePluginState.ide || !d.idePluginState.active ? this.javaPluginSectionOpened = !0 : (!(/* @__PURE__ */ new Set(["vscode", "intellij"])).has(d.idePluginState.ide) || !d.idePluginState.active) && (this.javaPluginSectionOpened = !1)), l.jdkInfo && x() !== "success" && (this.hotswapSectionOpened = !0);
      },
      { fireImmediately: !0 }
    );
  }
  createRenderRoot() {
    return this;
  }
  connectedCallback() {
    super.connectedCallback(), this.classList.add("contents"), y.on("set-up-vs-code-hotswap-status", this.onDownloadStatusUpdate);
  }
  disconnectedCallback() {
    super.disconnectedCallback(), y.off("set-up-vs-code-hotswap-status", this.onDownloadStatusUpdate);
  }
  render() {
    const e = {
      intellij: d.idePluginState?.ide === "intellij",
      vscode: d.idePluginState?.ide === "vscode",
      eclipse: d.idePluginState?.ide === "eclipse",
      idePluginInstalled: !!d.idePluginState?.active
    };
    return n`
      ${this.renderPluginSection(e)}
      <hr class="border-b border-e-0 border-s-0 border-t-0 mx-4 my-0" />
      ${this.renderHotswapSection(e)}
    `;
  }
  renderPluginSection(e) {
    let t = "";
    e.intellij ? t = "IntelliJ" : e.vscode ? t = "VS Code" : e.eclipse && (t = "Eclipse");
    let a, s;
    e.vscode || e.intellij ? e.idePluginInstalled ? (a = `Plugin for ${t} installed`, s = this.renderPluginInstalledContent()) : (a = `Plugin for ${t} not installed`, s = this.renderPluginIsNotInstalledContent(e)) : e.eclipse ? (a = e.idePluginInstalled ? "Eclipse plugin installed" : "Eclipse plugin not installed", s = e.idePluginInstalled ? this.renderPluginInstalledContent() : this.renderEclipsePluginContent()) : (a = "No IDE found", s = this.renderNoIdePluginContent());
    const i = e.idePluginInstalled ? r.checkCircle : r.warning;
    return n`
      <vaadin-details
        theme="reverse"
        .opened=${this.javaPluginSectionOpened}
        @opened-changed=${(o) => {
      I(() => {
        this.javaPluginSectionOpened = o.detail.value;
      }), this.requestLayoutUpdate();
    }}>
        <vaadin-details-summary class="px-4 py-3.5" slot="summary">
          <div class="flex gap-1.5">
            <vaadin-icon
              class="${e.idePluginInstalled ? "text-teal-11" : "text-ruby-11"}"
              .svg=${i}></vaadin-icon>
            <span>${a}</span>
          </div>
        </vaadin-details-summary>
        <div>${s}</div>
      </vaadin-details>
    `;
  }
  renderNoIdePluginContent() {
    return n`
      <div class="flex flex-col gap-2 pb-4 px-4">
        <p class="m-0 text-secondary">
          For the best development experience, use
          <a class="gap-1 inline-flex items-center" href="https://code.visualstudio.com"
            ><vaadin-icon class="icon-sm" .svg=${r.vsCode}></vaadin-icon>Visual Studio Code</a
          >
          or
          <a class="gap-1 inline-flex items-center" href="https://www.jetbrains.com/idea"
            ><vaadin-icon class="icon-sm" .svg=${r.intelliJ}></vaadin-icon>IntelliJ IDEA</a
          >.
        </p>
      </div>
    `;
  }
  renderEclipsePluginContent() {
    return n`
      <div class="flex flex-col gap-2 items-start pb-4 px-4">
        <p class="m-0 text-secondary">Install the Vaadin Eclipse Plugin to ensure a smooth development workflow</p>
        <p class="m-0 text-secondary">
          Installing the plugin is not required, but strongly recommended. Some Vaadin Copilot functionality, such as
          undo, will not function optimally without the plugin.
        </p>
        <vaadin-button
          class="mt-2"
          @click="${() => {
      window.open(S, "_blank");
    }}"
          >Install from Eclipse Marketplace
          <vaadin-icon slot="suffix" .svg="${r.arrowOutward}"></vaadin-icon>
        </vaadin-button>
      </div>
    `;
  }
  renderPluginInstalledContent() {
    return n`
      <p class="m-0 pb-4 px-4 text-secondary">You have a running plugin. Enjoy your awesome development workflow!</p>
    `;
  }
  renderPluginIsNotInstalledContent(e) {
    let t = null, a = "Install from Marketplace";
    return e.intellij ? (t = R, a = "Install from JetBrains Marketplace") : e.vscode ? (t = V, a = "Install from VSCode Marketplace") : e.eclipse && (t = S, a = "Install from Eclipse Marketplace"), n`
      <div class="flex flex-col gap-2 items-start pb-4 px-4">
        <p class="m-0 text-secondary">Install the Vaadin IDE Plugin to ensure a smooth development workflow</p>
        <p class="m-0 text-secondary">
          Installing the plugin is not required, but strongly recommended. Some Vaadin Copilot functionality, such as
          undo, will not function optimally without the plugin.
        </p>
        ${t ? n` <vaadin-button
              class="mt-2"
              @click="${() => {
      window.open(t, "_blank");
    }}"
              >${a}
              <vaadin-icon slot="suffix" .svg="${r.arrowOutward}"></vaadin-icon>
            </vaadin-button>` : p}
      </div>
    `;
  }
  getActiveTabContent(e, t) {
    return this.hotswapTab === "jrebel" ? t.jrebel ? this.renderJRebelInstalledContent() : this.renderJRebelNotInstalledContent() : e.intellij ? this.renderIntelliJHotswapHint() : e.vscode ? this.renderVSCodeHotswapHint() : this.renderHotswapAgentNotInstalledContent(e);
  }
  renderHotswapSection(e) {
    const { jdkInfo: t } = l;
    if (!t)
      return p;
    const a = x(), s = J();
    let i, o;
    a === "success" ? (i = r.checkCircle, o = "Java Hotswap is enabled") : a === "warning" ? (i = r.warning, o = "Java Hotswap is not enabled") : a === "error" && (i = r.warning, o = "Java Hotswap is partially enabled");
    const u = this.getActiveTabContent(e, t), w = s === "jrebel" ? this.renderJRebelInstalledContent() : this.renderHotswapAgentInstalledContent(), h = this.hotswapTab === "hotswapagent" ? 0 : 1;
    return n` <vaadin-details
      theme="reverse"
      .opened=${this.hotswapSectionOpened}
      @opened-changed=${(f) => {
      I(() => {
        this.hotswapSectionOpened = f.detail.value;
      }), this.requestLayoutUpdate();
    }}>
      <vaadin-details-summary class="px-4 py-3.5" slot="summary">
        <div class="flex gap-1.5">
          <vaadin-icon
            class="${a === "success" ? "text-teal-11" : "text-ruby-11"}"
            .svg=${i}></vaadin-icon>
          <span>${o}</span>
        </div>
      </vaadin-details-summary>
      <div>
        ${s === "none" ? n`
              <vaadin-tabs
                .selected=${h}
                @selected-changed=${(f) => {
      this.hotswapTab = f.detail.value === 0 ? "hotswapagent" : "jrebel";
    }}>
                <vaadin-tab>Hotswap Agent</vaadin-tab>
                <vaadin-tab>JRebel</vaadin-tab>
              </vaadin-tabs>
              ${u}
            ` : n`${w}`}
      </div>
    </vaadin-details>`;
  }
  renderJRebelNotInstalledContent() {
    return n`
      <div class="flex flex-col gap-2 p-4">
        <p class="m-0 text-secondary">
          <a class="inline-flex items-center" href="https://www.jrebel.com"
            >JRebel <vaadin-icon class="icon-sm" .svg=${r.arrowOutward}></vaadin-icon
          ></a>
          is a commercial hotswap solution. Vaadin detects the JRebel Agent and automatically reloads the application in
          the browser after the Java changes have been hotpatched.
        </p>
        <p class="m-0 text-secondary">
          Go to
          <a
            class="inline-flex items-center"
            href="https://www.jrebel.com/products/jrebel/learn"
            target="_blank"
            rel="noopener noreferrer">
            https://www.jrebel.com/products/jrebel/learn
            <vaadin-icon class="icon-sm" .svg=${r.arrowOutward}></vaadin-icon
          ></a>
          to get started.
        </p>
      </div>
    `;
  }
  renderHotswapAgentNotInstalledContent(e) {
    const t = [
      this.renderJavaRunningInDebugModeSection(),
      this.renderHotswapAgentJdkSection(e),
      this.renderInstallHotswapAgentJdkSection(e),
      this.renderHotswapAgentVersionSection(),
      this.renderHotswapAgentMissingArgParam(e)
    ];
    return n` <div class="p-2">${t}</div> `;
  }
  renderIntelliJHotswapHint() {
    return n` <div class="flex flex-col gap-2 p-4">
      <h3 class="font-semibold my-0 text-sm">Use 'Debug using Hotswap Agent' launch configuration</h3>
      <p class="m-0 text-secondary">
        Vaadin IntelliJ plugin offers launch mode that does not require any manual configuration!
      </p>
      <p class="m-0 text-secondary">
        In order to run recommended launch configuration, you should click three dots right next to Debug button and
        select
        <code class="bg-gray-3 dark:bg-gray-7 font-mono inline-flex px-1.5 py-px rounded-md text-body text-xs"
          >Debug using Hotswap Agent</code
        >
        option.
      </p>
    </div>`;
  }
  renderVSCodeHotswapHint() {
    return n` <div>
      <h3 class="font-semibold my-0 text-sm">Use 'Debug (hotswap)'</h3>
      With Vaadin Visual Studio Code extension you can run Hotswap Agent without any manual configuration required!
      <p class="m-0">
        Click
        <code class="bg-gray-3 dark:bg-gray-7 font-mono inline-flex px-1.5 py-px rounded-md text-body text-xs"
          >Debug (hotswap)</code
        >
        within your main class to debug application using Hotswap Agent.
      </p>
    </div>`;
  }
  renderJavaRunningInDebugModeSection() {
    const e = l.jdkInfo?.runningInJavaDebugMode;
    return n`
      <vaadin-details theme="reverse" .opened="${!e}">
        <vaadin-details-summary class="p-2" slot="summary">Run Java in debug mode</vaadin-details-summary>
        <p class="m-0 pb-2 px-2 text-secondary">Start the application in debug mode in the IDE.</p>
      </vaadin-details>
    `;
  }
  renderHotswapAgentMissingArgParam(e) {
    const t = l.jdkInfo?.runningWitHotswap && l.jdkInfo?.runningWithExtendClassDef;
    return n`
      <vaadin-details theme="reverse" .opened="${!t}">
        <vaadin-details-summary class="p-2" slot="summary">Enable HotswapAgent</vaadin-details-summary>
        <div class="flex flex-col gap-2 pb-2 px-2 text-secondary">
          <ul class="m-0 ps-4">
            ${e.intellij ? n`<li>Launch as mentioned in the previous step</li>` : p}
            ${e.intellij ? n`<li>
                  To manually configure IntelliJ, add the
                  <code
                    class="bg-gray-3 dark:bg-gray-7 break-all font-mono inline-flex px-1.5 py-px rounded-md text-body text-xs"
                    >-XX:HotswapAgent=fatjar -XX:+AllowEnhancedClassRedefinition -XX:+UpdateClasses</code
                  >
                  JVM arguments when launching the application.
                </li>` : n`<li>
                  Add the
                  <code
                    class="bg-gray-3 dark:bg-gray-7 break-all font-mono inline-flex px-1.5 py-px rounded-md text-body text-xs"
                    >-XX:HotswapAgent=fatjar -XX:+AllowEnhancedClassRedefinition -XX:+UpdateClasses</code
                  >
                  JVM arguments when launching the application.
                </li>`}
          </ul>
        </div>
      </vaadin-details>
    `;
  }
  renderHotswapAgentJdkSection(e) {
    const t = l.jdkInfo?.extendedClassDefCapable, a = this.downloadStatusMessages?.[this.downloadStatusMessages.length - 1] === P, s = this.downloadProgress > 0 ? n`<vaadin-progress-bar .value="${this.downloadProgress}" min="0" max="1"></vaadin-progress-bar>` : p, i = a ? n`<h3 class="font-semibold my-0 text-sm">
          Go to VS Code and launch the 'Debug using Hotswap Agent' configuration
        </h3>` : p;
    return n`
      <vaadin-details theme="reverse" .opened="${!t}">
        <vaadin-details-summary class="p-2" slot="summary">Run using JetBrains Runtime JDK</vaadin-details-summary>
        <div class="flex flex-col gap-2 pb-2 px-2 text-secondary">
          <p class="m-0">JetBrains Runtime provides much better hotswapping compared to other JDKs.</p>
          <ul class="m-0 ps-4">
            ${e.intellij && N("1.3.0", d.idePluginState?.version) ? n` <li>Upgrade to the latest IntelliJ plugin</li>` : p}
            ${e.intellij ? n` <li>Launch the application in IntelliJ using "Debug using Hotswap Agent"</li>` : p}
            ${e.vscode ? n` <li>
                  <a href @click="${(o) => this.downloadJetbrainsRuntime(o)}"
                    >Let Copilot download and set up JetBrains Runtime for VS Code</a
                  >
                  ${s}
                  <ul>
                    ${this.downloadStatusMessages.map((o) => n`<li>${o}</li>`)} ${i}
                  </ul>
                </li>` : p}
            <li>
              ${e.intellij || e.vscode ? n`If there is a problem, you can manually
                    <a target="_blank" href="${C}">download JetBrains Runtime JDK</a> and set up your
                    debug configuration to use it.` : n`<a target="_blank" href="${C}">Download JetBrains Runtime JDK</a> and set up
                    your debug configuration to use it.`}
            </li>
          </ul>
        </div>
      </vaadin-details>
    `;
  }
  renderInstallHotswapAgentJdkSection(e) {
    const t = l.jdkInfo?.hotswapAgentFound, a = l.jdkInfo?.extendedClassDefCapable;
    return n`
      <vaadin-details theme="reverse" .opened="${!t}">
        <vaadin-details-summary class="p-2" slot="summary"> Install HotswapAgent </vaadin-details-summary>
        <div class="flex flex-col gap-2 pb-2 px-2 text-secondary">
          <p class="m-0">
            Hotswap Agent provides application level support for hot reloading, such as reinitalizing Vaadin @Route or
            @BrowserCallable classes when they are updated.
          </p>
          <ul class="m-0 ps-4">
            ${e.intellij ? n`<li>Launch as mentioned in the previous step</li>` : p}
            ${!e.intellij && !a ? n`<li>First install JetBrains Runtime as mentioned in the step above.</li>` : p}
            ${e.intellij ? n`<li>
                  To manually configure IntelliJ, download HotswapAgent and install the jar file as
                  <code class="bg-gray-3 dark:bg-gray-7 font-mono inline-flex px-1.5 py-px rounded-md text-body text-xs"
                    >[JAVA_HOME]/lib/hotswap/hotswap-agent.jar</code
                  >
                  in the JetBrains Runtime JDK. Note that the file must be renamed to exactly match this path.
                </li>` : n`<li>
                  Download HotswapAgent and install the jar file as
                  <code class="bg-gray-3 dark:bg-gray-7 font-mono inline-flex px-1.5 py-px rounded-md text-body text-xs"
                    >[JAVA_HOME]/lib/hotswap/hotswap-agent.jar</code
                  >
                  in the JetBrains Runtime JDK. Note that the file must be renamed to exactly match this path.
                </li>`}
          </ul>
        </div>
      </vaadin-details>
    `;
  }
  renderHotswapAgentVersionSection() {
    if (!l.jdkInfo?.hotswapAgentFound)
      return p;
    const e = l.jdkInfo?.hotswapVersionOk, t = l.jdkInfo?.hotswapVersion, a = l.jdkInfo?.hotswapAgentLocation;
    return n`
      <vaadin-details theme="reverse" .opened="${!e}">
        <vaadin-details-summary class="p-2" slot="summary">Hotswap version requires update</vaadin-details-summary>
        <div>
          HotswapAgent version ${t} is in use
          <a target="_blank" href="https://github.com/HotswapProjects/HotswapAgent/releases"
            >Download the latest HotswapAgent</a
          >
          and place it in
          <code class="bg-gray-3 dark:bg-gray-7 font-mono inline-flex px-1.5 py-px rounded-md text-body text-xs"
            >${a}</code
          >
        </div>
      </vaadin-details>
    `;
  }
  renderJRebelInstalledContent() {
    return n` <p class="m-0 pb-2 px-2">JRebel is in use. Enjoy your awesome development workflow!</p> `;
  }
  renderHotswapAgentInstalledContent() {
    return n`
      <p class="m-0 pb-4 px-4 text-secondary">Hotswap agent is in use. Enjoy your awesome development workflow!</p>
    `;
  }
  async downloadJetbrainsRuntime(e) {
    return e.target.disabled = !0, e.preventDefault(), this.downloadStatusMessages = [], H(`${E}set-up-vs-code-hotswap`, {}, (t) => {
      t.data.error ? (D("Error downloading JetBrains runtime", t.data.error), this.downloadStatusMessages = [...this.downloadStatusMessages, "Download failed"]) : this.downloadStatusMessages = [...this.downloadStatusMessages, P];
    });
  }
  downloadStatusUpdate(e) {
    const t = e.detail.progress;
    t ? this.downloadProgress = t : this.downloadStatusMessages = [...this.downloadStatusMessages, e.detail.message];
  }
};
c.NAME = "copilot-development-setup-user-guide";
g([
  m()
], c.prototype, "javaPluginSectionOpened", 2);
g([
  m()
], c.prototype, "hotswapSectionOpened", 2);
g([
  m()
], c.prototype, "hotswapTab", 2);
g([
  m()
], c.prototype, "downloadStatusMessages", 2);
g([
  m()
], c.prototype, "downloadProgress", 2);
c = g([
  k(c.NAME)
], c);
let j = class extends M {
  createRenderRoot() {
    return this;
  }
  connectedCallback() {
    super.connectedCallback(), this.classList.add("contents");
  }
  render() {
    return n`<vaadin-button
      id="close"
      @click="${() => b.closePanel(v.tag)}"
      >Close
    </vaadin-button>`;
  }
};
j = g([
  k("copilot-development-setup-footer-actions")
], j);
const v = {
  header: "Development Workflow",
  tag: A,
  footerActionsTag: "copilot-development-setup-footer-actions",
  individual: !0
}, L = {
  init(e) {
    e.addPanel(v);
  }
};
globalThis.Vaadin.copilot.plugins.push(L);
b.addPanel(v);
export {
  j as CopilotDevelopmentSetupFooterActions,
  c as CopilotDevelopmentSetupUserGuide,
  v as copilotDevelopmentSetupPanelConfig
};
