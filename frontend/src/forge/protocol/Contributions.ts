import type { MenuItem } from "./MenuItem";
import type { Keybinding } from "./Keybinding";
import type { ViewContribution } from "./ViewContribution";
export interface Contributions {
  menus: MenuItem[];
  keybindings: Keybinding[];
  views: ViewContribution[];
}
