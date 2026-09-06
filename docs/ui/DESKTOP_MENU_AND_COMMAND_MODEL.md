# Desktop Menu and Command Model

`DesktopApplication::commands()` is the canonical registry. Its 46 stable records carry ID, label, category, SVG key, platform shortcut, optional destination, workspace-navigation membership and enabled state. Native Windows menu actions, macOS `QAction`s, platform shortcuts and the command palette all call `Desktop.invokeCommand(id)`.

## Platform menus

macOS uses a native `QMenuBar` created in C++ and suppresses the QML in-window menu. Qt menu roles place About, Settings and Quit into the ShackCQ app menu. File, Edit, View, Radio, Navigate, Window and Help use Command shortcuts and system window chrome. Services/Hide/Show All remain platform managed.

Windows uses an `HMENU` attached to the `QQuickWindow` HWND with `&File`, `&Edit`, `&View`, `&Radio`, `&Navigate`, `&Tools`, `&Window`, and `&Help`. It occupies native window chrome, preserves Alt access, and leaves the entire QML client area to the active workspace. The offscreen gallery skips HWND attachment; source/hosted compilation proves that path, while physical rendering remains separately pending.

## Enablement and safety

Commands without a completed service are visible where the programme requires discoverability but disabled; they cannot dispatch. Fast Entry and ADIF import/export route to the real Logbook dialogs. Export Configuration routes to Settings. Disconnect calls the radio controller. Global Stop calls the existing fail-closed stop chain. Connect remains disabled because a profile/route must be selected in the Radio workspace rather than guessed.

Escape, the visible header/Shack actions, both platform menus, and the command palette resolve to `radio.stop`. Text editing keeps standard Undo/Redo/Cut/Copy/Paste/Delete/Select All actions through the current focus object; Find remains disabled until a real workspace search owner exists.

`desktop_ui_contract_tests` proves stable unique IDs, 19 unique navigation destinations, required safety/navigation commands, SVG coverage, native platform shell markers and the absence of an in-window application menu. Deep Convergence v2 adds the grouped workspace sidebar as a command-registry consumer; it does not replace the native menu or command palette.
