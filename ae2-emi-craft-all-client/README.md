# AE2 EMI Craft All Client Helper

Client-side NeoForge helper mod for using EMI craft-all actions inside AE2 crafting terminals.

This is intentionally kept as a small standalone helper instead of changing EMI's core code, because the behavior depends on AE2 client menus and AE2 network packets.

## Behavior

- Handles EMI craft-all requests from AE2 crafting terminals.
- Repeatedly completes normal 3x3 crafting outputs instead of only filling the grid.
- Sends intermediate crafting results back into the AE2 network.
- Sends selected EMI tree root results to the player inventory first, then falls back to AE2 if inventory deposit fails.
- Falls back to AE2 `AUTO_CRAFT` for AE2 pattern-only craftable entries.
- For dynamic EMI recipes with no backing recipe, waits for AE2's default EMI handler to fill the grid, then clicks a matching output slot once.

## Notes

- Client-side only; no server protocol changes.
- Depends on AE2's EMI recipe handler implementation.
- Uses EMI internal BoM state to identify the selected tree root recipe.
- The dynamic post-fill fallback is limited to one batch and only clicks exact item/component output matches.
