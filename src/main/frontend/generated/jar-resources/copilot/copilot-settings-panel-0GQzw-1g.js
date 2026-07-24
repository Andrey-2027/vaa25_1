import { n as m, al as y, a1 as s, w as $, x as o, e as t, D as h, E as u, am as i, u as g, f as C, an as w, P as S, r as b, p as T } from "./copilot-CdD0jRv0.js";
import { L as k } from "./lit-renderer-CROrG6ef.js";
import { B as P } from "./base-panel-DqYmxkT9.js";
/**
 * @license
 * Copyright (c) 2017 - 2025 Vaadin Ltd.
 * This program is available under Apache License Version 2.0, available at https://vaadin.com/license/
 */
class A extends k {
  /**
   * Adds the renderer callback to the select.
   */
  addRenderer() {
    this.element.renderer = (l, n) => {
      this.renderRenderer(l, n);
    };
  }
  /**
   * Runs the renderer callback on the select.
   */
  runRenderer() {
    this.element.requestContentUpdate();
  }
  /**
   * Removes the renderer callback from the select.
   */
  removeRenderer() {
    this.element.renderer = null;
  }
}
const x = m(A), f = window.Vaadin.copilot.tree;
if (!f)
  throw new Error("Tried to access copilot tree before it was initialized.");
var E = Object.defineProperty, I = Object.getOwnPropertyDescriptor, p = (a, l, n, d) => {
  for (var e = d > 1 ? void 0 : d ? I(l, n) : l, r = a.length - 1, v; r >= 0; r--)
    (v = a[r]) && (e = (d ? v(l, n, e) : v(e)) || e);
  return d && e && E(l, n, e), e;
};
let c = class extends P {
  constructor() {
    super(...arguments), this.selectedTab = 0, this.activationShortcutEnabled = s.isActivationShortcut(), this.aiUsage = s.isAIUsageAllowed(), this.hideCopilotRequestOngoing = !1, this.hideCopilotDialogVisible = !1, this.sizeItems = [
      { label: "Default", value: "default" },
      { label: "Compact", value: "compact" }
    ], this.themeItems = [
      { label: "System", value: "system" },
      { label: "Light", value: "light" },
      { label: "Dark", value: "dark" }
    ], this.toolbarExpandModeItems = [
      {
        label: "Proximity",
        value: "proximity",
        description: "The toolbar expands and becomes fully visible as the mouse pointer approaches it."
      },
      {
        label: "Click",
        value: "click",
        description: "The toolbar expands and becomes fully visible when Play mode is clicked."
      },
      {
        label: "Hover",
        value: "hover",
        description: "The toolbar expands and becomes fully visible when the mouse hovers over it."
      },
      {
        label: "Always expanded",
        value: "always",
        description: "The toolbar remains fully visible at all times and never collapses or becomes translucent."
      },
      {
        label: "Disabled",
        value: "never",
        description: "Only Play mode is visible. Changing Copilot mode is not available, and keyboard shortcuts are disabled."
      }
    ], this.badgePositionItems = [
      {
        label: "Smart",
        value: "smart",
        description: "Automatically finds the best position by avoiding overlaps with nearby elements."
      },
      {
        label: "Static",
        value: "static",
        description: "Keeps the badge in a predefined position regardless of surrounding elements."
      }
    ], this.aiUsageItems = [
      { label: "Ask each time", value: "ask" },
      { label: "Allow", value: "yes" },
      { label: "Deny", value: "no" }
    ], this.aiProviderItems = [
      { label: "Any region", value: "ANY" },
      { label: "EU only", value: "EU_ONLY" }
    ], this.toggleActivationShortcut = () => {
      this.activationShortcutEnabled = !this.activationShortcutEnabled, s.setActivationShortcut(this.activationShortcutEnabled);
    };
  }
  connectedCallback() {
    super.connectedCallback(), this.classList.add("flex", "flex-col", "h-full");
  }
  updated(a) {
    super.updated(a);
  }
  renderKbd(a) {
    const l = a.replace(/<kbd([^>]*)class="([^"]*)"/, '<kbd$1class="$2 font-sans ms-auto"').replace(/<kbd(?![^>]*class=)/, '<kbd class="font-sans ms-auto"');
    return $(l);
  }
  render() {
    return o`
      <vaadin-tabs>
        <vaadin-tab ?selected=${this.selectedTab === 0} @click=${() => this.selectedTab = 0}>General</vaadin-tab>
        <vaadin-tab ?selected=${this.selectedTab === 1} @click=${() => this.selectedTab = 1}>Shortcuts</vaadin-tab>
        <vaadin-tab ?selected=${this.selectedTab === 2} @click=${() => this.selectedTab = 2}>AI</vaadin-tab>
      </vaadin-tabs>
      ${this.selectedTab === 0 ? this.renderGeneralTab() : null}
      ${this.selectedTab === 1 ? this.renderShortcutsTab() : null} ${this.selectedTab === 2 ? this.renderAiTab() : null}
    `;
  }
  renderGeneralTab() {
    const a = s.getSelectedSize(), l = s.getSelectedTheme(), n = s.getToolbarExpandMode(), d = s.getBadgePositionMode();
    return o`
      <div class="border-dashed flex flex-col flex-grow divide-y pb-4 pt-0.5 px-4" part="general-tab-container">
        <div class="flex gap-2 items-start justify-between py-2">
          <label class="py-1.5" id="size">Size</label>
          <vaadin-select
            accessible-name-ref="size"
            class="flex-shrink-0"
            theme="auto-width no-border"
            .items="${this.sizeItems}"
            .value="${a}"
            @change="${(e) => {
      s.setSelectedSize(e.target.value);
    }}"></vaadin-select>
        </div>
        <div class="flex gap-2 items-start justify-between py-2">
          <label class="py-1.5" id="theme">Theme</label>
          <vaadin-select
            accessible-name-ref="theme"
            class="flex-shrink-0"
            theme="auto-width no-border"
            .items="${this.themeItems}"
            .value="${l}"
            @change="${(e) => {
      s.setSelectedTheme(
        e.target.value
      );
    }}"></vaadin-select>
        </div>
        <div class="flex gap-2 items-start justify-between py-2">
          <div class="flex flex-col py-1.5">
            <label id="toolbar-button-expand-mode">Toolbar behavior</label>
            <span class="text-secondary text-xs">How it appears & expands</span>
          </div>
          <vaadin-select
            accessible-name-ref="toolbar-expand-mode"
            class="flex-shrink-0"
            theme="auto-width no-border"
            .value="${n}"
            ${x(
      () => o`
                <vaadin-list-box class="max-w-xs">
                  ${this.toolbarExpandModeItems.map(
        (e) => o`
                      <vaadin-item class="items-start" label="${e.label}" value="${e.value}">
                        <span class="flex flex-col gap-0.5">
                          <span>${e.label}</span>
                          <span class="text-secondary text-xs">${e.description}</span>
                        </span>
                      </vaadin-item>
                    `
      )}
                </vaadin-list-box>
              `
    )}
            @change="${(e) => {
      const r = s.getToolbarExpandMode();
      s.setToolbarExpandMode(
        e.target.value
      ), h("toolbar-expand-mode-change", {
        selected: s.getToolbarExpandMode(),
        previous: r
      });
    }}"></vaadin-select>
        </div>
        <div class="flex gap-2 items-start justify-between py-2">
          <div class="flex flex-col py-1.5">
            <label id="toolbar-button-expand-mode">Badge positioning</label>
            <span class="text-secondary text-xs">How it is placed</span>
          </div>
          <vaadin-select
            accessible-name-ref="badge-position-mode"
            class="flex-shrink-0"
            theme="auto-width no-border"
            .value="${d}"
            ${x(
      () => o`
                <vaadin-list-box class="max-w-xs">
                  ${this.badgePositionItems.map(
        (e) => o`
                      <vaadin-item class="items-start" label="${e.label}" value="${e.value}">
                        <span class="flex flex-col gap-0.5">
                          <span>${e.label}</span>
                          <span class="text-secondary text-xs">${e.description}</span>
                        </span>
                      </vaadin-item>
                    `
      )}
                </vaadin-list-box>
              `
    )}
            @change="${(e) => {
      const r = s.getBadgePositionMode();
      s.setBadgePositionMode(
        e.target.value
      ), h("badge-position-mode-changed", {
        selected: s.getBadgePositionMode(),
        previous: r
      });
    }}"></vaadin-select>
        </div>
        <div class="flex gap-2 justify-between py-3.5">
          <div class="flex flex-col">
            <label id="reduce-motion-label">Reduce motion</label>
            <span id="reduce-motion-desc" class="text-secondary text-xs">Disables animations</span>
          </div>
          <button
            aria-checked="true"
            aria-labelledby="reduce-motion-label"
            aria-describedby="reduce-motion-desc"
            class="my-px"
            role="switch"
            type="button">
            <span></span>
          </button>
        </div>
        <div class="flex gap-2 justify-between mb-4 py-3.5">
          <div class="flex flex-col">
            <label id="error-reports-label">Send error reports</label>
            <span id="error-reports-desc" class="text-secondary text-xs">Helps us improve the user experience</span>
          </div>
          <button
            aria-checked="true"
            aria-labelledby="error-reports-label"
            aria-describedby="error-reports-desc"
            class="my-px"
            role="switch"
            type="button">
            <span></span>
          </button>
        </div>
        <vaadin-button
          data-test-id="hide-copilot-btn"
          @click="${this.handleHideCopilotButtonClick}"
          class="justify-start mt-auto self-start">
          <vaadin-icon slot="prefix" .svg="${t.close}"></vaadin-icon>
          Hide Copilot until server restart
        </vaadin-button>
      </div>

      <vaadin-confirm-dialog
        id="hideCopilotDialog"
        header="Temporarily Hide Copilot"
        .confirmText=${this.hideCopilotRequestOngoing ? "Hiding…" : "Continue"}
        cancel-text="Cancel"
        cancel-button-visible
        confirm-theme="primary"
        .confirmDisabled=${this.hideCopilotRequestOngoing}
        .cancelDisabled=${this.hideCopilotRequestOngoing}
        .noCloseOnEsc=${this.hideCopilotRequestOngoing}
        .opened="${this.hideCopilotDialogVisible}"
        .noCloseOnOutsideClick=${this.hideCopilotRequestOngoing}
        @cancel=${() => {
      this.hideCopilotDialogVisible = !1;
    }}
        @confirm=${this.onDisableConfirm}>
        This will hide the Copilot until the server restarts. The page will reload to apply the change. Do you want to
        continue?
        ${this.hideCopilotRequestOngoing ? o`
              <div style="display:flex; align-items:center; gap:var(--lumo-space-s); margin-top:var(--lumo-space-m);">
                <vaadin-progress-indicator indeterminate></vaadin-progress-indicator>
                <span>Hiding…</span>
              </div>
            ` : null}
      </vaadin-confirm-dialog>
    `;
  }
  renderShortcutsTab() {
    const a = f.hasFlowComponents();
    return o`<div class="flex flex-col gap-4 pb-2 pt-4 px-4 ">
      <div class="flex justify-between">
        <div class="flex flex-col">
          <label id="enable-shortcuts-label">Enable keyboard shortcut</label>
          <span id="enable-shortcuts-desc" class="text-secondary text-xs"
            >Switch anytime to Play mode with ${this.renderKbd(i.toggleCopilot)}</span
          >
        </div>
        <button
          aria-checked="${this.activationShortcutEnabled}"
          aria-labelledby="enable-shortcuts-label"
          aria-describedby="enable-shortcuts-desc"
          class="my-px"
          role="switch"
          type="button"
          @click=${() => this.toggleActivationShortcut()}>
          <span></span>
        </button>
      </div>
      <div class="flex flex-col gap-1">
        <h3 class="font-semibold my-0 text-sm">Global</h3>
        <ul class="border-dashed divide-y flex flex-col list-none m-0 p-0">
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.vaadin}"></vaadin-icon>
            <span>Switch Play Mode / Last Mode</span>
            ${this.renderKbd(i.toggleCopilot)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.undo}"></vaadin-icon>
            <span>Undo</span>
            ${this.renderKbd(i.undo)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.redo}"></vaadin-icon>
            <span>Redo</span>
            ${this.renderKbd(i.redo)}
          </li>
        </ul>
      </div>
      <div class="flex flex-col gap-1">
        <h3 class="font-semibold my-0 text-sm">Component Selection</h3>
        <ul class="border-dashed divide-y flex flex-col list-none m-0 p-0">
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.sparkles}"></vaadin-icon>
            <span>Open AI prompt</span>
            ${this.renderKbd(i.openAiPopover)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.code}"></vaadin-icon>
            <span>Go to source</span>
            ${this.renderKbd(i.goToSource)}
          </li>
          ${a ? o`<li class="flex gap-2 py-2">
                <vaadin-icon .svg="${t.code}"></vaadin-icon>
                <span>Go to attach source</span>
                ${this.renderKbd(i.goToAttachSource)}
              </li>` : u}
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.contentCopy}"></vaadin-icon>
            <span>Copy</span>
            ${this.renderKbd(i.copy)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.contentPaste}"></vaadin-icon>
            <span>Paste</span>
            ${this.renderKbd(i.paste)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.fileCopy}"></vaadin-icon>
            <span>Duplicate</span>
            ${this.renderKbd(i.duplicate)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.turnLeft}"></vaadin-icon>
            <span>Select parent</span>
            ${this.renderKbd(i.selectParent)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.north}"></vaadin-icon>
            <span>Select previous sibling</span>
            ${this.renderKbd(i.selectPreviousSibling)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.south}"></vaadin-icon>
            <span>Select first child / next sibling</span>
            ${this.renderKbd(i.selectNextSibling)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.delete}"></vaadin-icon>
            <span>Delete</span>
            ${this.renderKbd(i.delete)}
          </li>
          <li class="flex gap-2 py-2">
            <vaadin-icon .svg="${t.dashboardCustomize}"></vaadin-icon>
            <span>Add component</span>
            <kbd class="font-sans ms-auto">A – Z</kbd>
          </li>
        </ul>
      </div>
    </div>`;
  }
  renderAiTab() {
    const a = g.userInfo?.copilotProjectCannotLeaveLocalhost ?? !1, l = a ? "no" : this.aiUsage, n = g.userInfo?.copilotProjectCannotLeaveEU ? "EU_ONLY" : "ANY";
    return o`<div class="border-dashed flex flex-col divide-y px-4 py-0.5">
      <div class="flex gap-2 items-start justify-between py-2">
        <div class="flex flex-col py-1.5">
          <label id="ai-usage">AI usage</label>
          <span class="text-secondary text-xs">All AI features are clearly labelled </span>
          ${a ? o`<span class="text-secondary text-xs"
                >Restricted for your account. Ask your Vaadin account manager to change it.</span
              >` : u}
        </div>
        <vaadin-select
          accessible-name-ref="ai-usage"
          class="flex-shrink-0"
          theme="auto-width no-border"
          .items="${this.aiUsageItems}"
          .value="${l}"
          ?disabled="${a}"
          @value-changed="${(d) => {
      a || (this.aiUsage = d.detail.value, s.setAIUsageAllowed(d.detail.value));
    }}"></vaadin-select>
      </div>
      <div class="flex gap-2 items-start justify-between py-2">
        <div class="flex flex-col py-1.5">
          <label id="ai-provider">AI provider</label>
          <span class="text-secondary text-xs">Restricted at account level, contact Vaadin to modify it.</span>
        </div>
        <vaadin-select
          accessible-name-ref="ai-provider"
          class="flex-shrink-0"
          theme="auto-width no-border"
          .items="${this.aiProviderItems}"
          .value="${n}"
          disabled></vaadin-select>
      </div>
    </div>`;
  }
  handleHideCopilotButtonClick() {
    this.hideCopilotDialogVisible = !0;
  }
  onDisableConfirm() {
    this.hideCopilotRequestOngoing = !0, C(`${S}hide-copilot`, {}, (a) => {
      w(a.data, {}) || (this.hideCopilotRequestOngoing = !1, window.location.reload());
    });
  }
};
p([
  b()
], c.prototype, "selectedTab", 2);
p([
  b()
], c.prototype, "activationShortcutEnabled", 2);
p([
  b()
], c.prototype, "aiUsage", 2);
p([
  b()
], c.prototype, "hideCopilotRequestOngoing", 2);
p([
  b()
], c.prototype, "hideCopilotDialogVisible", 2);
c = p([
  T("copilot-settings-panel")
], c);
const D = {
  header: "Settings",
  tag: y.SETTINGS
}, R = {
  init(a) {
    a.addPanel(D);
  }
};
window.Vaadin.copilot.plugins.push(R);
export {
  c as CopilotSettingsPanel,
  D as panelConfig
};
