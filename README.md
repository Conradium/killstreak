# KillstreakNametags

A Paper plugin for PvP servers that puts a coloured **▼** marker above every player's head so others can see how dangerous they are at a glance. The colour is set by the player's kill count, and it fades back down if they stop killing for a while.

| Marker | Meaning (default config) |
|--------|--------------------------|
| 🟢 Green ▼ | 0 kills, harmless |
| 🟡 Yellow ▼ | 1 to 4 kills |
| 🔴 Red ▼ | 5 or more kills |

## Features

- **Floating kill-tier marker:** a `TextDisplay` entity follows each player every tick, sitting just above their nametag and always facing the viewer.
- **Kill tracking:** a kill counts when a player dies from damage dealt directly by another player.
- **Tier decay:** if a player goes long enough without a kill, their tier decays. The timer resets every time they get a kill.
- **Sneak to hide:** crouching removes your marker, and it comes back when you stand up.
- **Admin tools:** set anyone's kill count by hand or reload the config without restarting.

## Requirements

- Paper 1.21 (built against `paper-api 1.21-R0.1-SNAPSHOT`)
- Java 21

## Installation

1. Build the jar (see below) or grab `KillstreakNametags-<version>.jar`.
2. Drop it into your server's `plugins/` folder.
3. Restart the server. A default `config.yml` is created in `plugins/KillstreakNametags/`.

## Commands

Aliases: `/kstags`, `/nametags`

| Command | Description | Permission |
|---------|-------------|------------|
| `/ksname` | Show the help menu | `ksname.use` |
| `/ksname version` | Show the plugin version | `ksname.use` |
| `/ksname reload` | Reload `config.yml` and redraw every marker | `ksname.reload` |
| `/ksname setkills <player> <kills>` | Set an online player's kill count | `ksname.setkills` |

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `ksname.use` | everyone | Use the base `/ksname` command |
| `ksname.reload` | op | Reload the plugin configuration |
| `ksname.setkills` | op | Set a player's kill count manually |

## Configuration

```yaml
tiers:
  green-max-kills: 0      # up to this many kills shows green
  yellow-min-kills: 1     # from here until red-min-kills shows yellow
  red-min-kills: 5        # this many kills or more shows red

decay:
  red-to-yellow-duration-minutes: 10080    # 7 days without a kill
  yellow-to-green-duration-minutes: 1440   # 1 day without a kill
```

If the thresholds overlap (for example, yellow is not higher than green), the plugin fixes them on load and logs a warning.

## Notes

- Kill counts are kept in memory only, so they reset when the server restarts.
- The decay check runs once a minute for online players.

## Building

```bash
mvn clean package
```

The shaded jar is written to `target/KillstreakNametags-<version>.jar`.
