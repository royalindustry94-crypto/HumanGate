# Leon Companion Android prototype

Prototype goal: keep Leon visible above other Android apps using the system overlay permission.

Current build:
- draggable always-on overlay
- starts automatically after boot when overlay permission is already granted
- restores itself after screen-on / unlock events
- foreground service for persistence
- subtle idle breathing / floating movement
- tap Leon to open the control screen
- local conversation-shell screen
- no voice yet
- no live AI backend yet

Install the debug APK, open Leon Companion once, tap **Enable Leon**, then grant **Display over other apps** permission.
