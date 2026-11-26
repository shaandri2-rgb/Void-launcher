# VOID Ranks

Native Paper plugin for VOID SMP. Target: Paper 1.21.11 / Java 21.

## Commands
- `/alogin <key>` — authenticate as staff.
- `/achange <new-key>` — change your key (minimum 8 characters).
- `/agrade <player> <1-12>` — Owner only; assigns a grade and creates a temporary 5-character key.
- `/makeadmin <player>` — grades 9 (Manager) and 10 (Dev) only; creates grade 1 Helper.
- `/re <player>` — grades 1-12; enter spectator mode at the target.
- `/re` — stop spectating and return to saved location/game mode.
- `/vrank list`, `/vrank info <player>`, `/vrank reload`.

## Grades
0 Member; 1 Helper; 2 Junior Moderator; 3 Moderator; 4 Junior Administrator; 5 Administrator; 6 Curator; 7 Creator; 8 Deputy Manager; 9 Manager; 10 Dev; 11 CEO; 12 Owner.

## Build
Install JDK 21 and Maven. Run `mvn package`. Upload `target/VOIDRanks.jar` to the server's `plugins` folder and restart.

## Security and compatibility
- No LuckPerms, Vault, TAB, or other rank plugin dependency.
- No staff grade receives OP or wildcard permissions.
- First-run bootstrap key is generated and printed to server console. Restrict console access, log in once, then immediately run `/achange <long-secret>`.
- Keys are stored as salted PBKDF2-HMAC-SHA256 hashes. Failed login attempts are rate-limited.
- Third-party plugin permissions are intentionally not guessed. Add verified nodes to each grade's `permissions` list in `plugins/VOIDRanks/config.yml`.
- CoreProtect is included in the intended integration plan; install it later and then add its verified permission nodes.
- Donut SMP All In One handles its own economy/shards; Vault is not required by VOID Ranks.
