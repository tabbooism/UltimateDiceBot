# UltimateDiceBot Pro v3.0

UltimateDiceBot Pro is an elite, production-ready dice-betting bot for Old School RuneScape (OSRS) designed for use with the DreamBot API (latest version 4.1.73.1+). It features zero-error execution, a fully customizable GUI, and advanced security and anti-ban mechanisms.

## 🎯 Deliverables

1.  **UltimateDiceBot.java**: The main bot script containing all game logic, trade handling, bankroll management, and GUI.
2.  **deploy.ps1**: A hardened, self-adaptive deployment script for Windows to automate compilation and installation.

## 📋 Core Features

### ✅ Game Logic & Betting
-   **Dice Mechanics**: Uses two six-sided dice (sum 2–12).
-   **Configurable Payouts**: Payout table editable via GUI (default: 7 → 2×, 9/12 → 4×).
-   **Currency Support**: Full support for platinum tokens (ID 13204) and coins.
-   **Dynamic Max Bet**: Automatically capped at 10% of the current bankroll.
-   **Fee System**: Optional percentage fee on winnings.

### ✅ Trade Handling & Security
-   **Automated Trading**: Detects and processes incoming trade requests.
-   **Anti-Lure**: Rejects any non-currency items automatically.
-   **Spam Protection**: Configurable trade spam limits and automatic blacklisting.
-   **Debt Logging**: Records unpaid winnings if payouts fail and notifies via Discord/in-game chat.

### ✅ Bankroll & Funds Management
-   **Source Selection**: Choose between Inventory Only or Bank & Inventory modes.
-   **Auto-Withdrawal**: Low-funds threshold triggers automatic bank withdrawal.
-   **Real-time Tracking**: Monitors total bankroll (coins + converted tokens).

### ✅ Anti-Ban & Humanization
-   **Natural Movements**: Random camera rotations and skill-tab flicking.
-   **Idle Breaks**: Periodic breaks to simulate human behavior.
-   **Customizable**: All humanization features can be toggled via the GUI.

### ✅ Notifications & Dashboard
-   **Discord Webhooks**: Real-time notifications for wins, losses, debts, low funds, and blacklist events.
-   **GUI Dashboard**: Tabbed interface for all settings and a live status bar showing profit and state.

## 🔧 Deployment (deploy.ps1)

The included `deploy.ps1` script is designed to be error-proof and self-adaptive:
1.  **Locates Java**: Automatically finds the JDK compiler (`javac`).
2.  **Locates client.jar**: Searches common paths and running processes for the DreamBot API jar.
3.  **Compiles & Packages**: Builds the script into a JAR file compatible with DreamBot.
4.  **Installs**: Automatically copies the JAR to the DreamBot scripts folder.

## 🚀 Getting Started

1.  Clone this repository to your local machine.
2.  Ensure you have **JDK 11+** installed and added to your system PATH.
3.  Right-click `deploy.ps1` and select **"Run with PowerShell"**.
4.  Open DreamBot, refresh your local scripts, and start **UltimateDiceBot Pro**.
5.  Configure your settings in the GUI and click **"Start UltimateDiceBot Pro"**.

## 🛡️ API Compatibility

This script is built for **DreamBot 4.0+** and is tested on **4.1.73.1**. All API calls are wrapped in robust error handling with graceful fallbacks to ensure continuous operation across different API versions.

---

*Disclaimer: Use this script at your own risk. Always follow the rules of the game to avoid account penalties.*
