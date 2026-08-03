import org.dreambot.api.methods.Calculations;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.input.Camera;
import org.dreambot.api.input.Keyboard;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.tabs.Tab;
import org.dreambot.api.methods.tabs.Tabs;
import org.dreambot.api.methods.trade.Trade;
import org.dreambot.api.methods.walking.impl.Walking;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.Category;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.utilities.Logger;
import org.dreambot.api.utilities.Sleep;
import org.dreambot.api.wrappers.interactive.Player;
import org.dreambot.api.wrappers.items.Item;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

// ================================================================================
// UltimateDiceBot Pro v2.0
// A fully-featured OSRS dice-betting bot for DreamBot with:
//   - Platinum token support (item ID 13204, 1 token = 1000 coins)
//   - Dynamic payout table (user-configurable via GUI)
//   - Robust trade flow with 3 retries + currency fallback + debt logging
//   - Bank & inventory bankroll management
//   - Dynamic max bet (10% of current bankroll)
//   - Discord webhook notifications
//   - Anti-ban (camera, breaks, human-like delays)
//   - Thread-safe blacklist and spam detection
//   - Tabbed Swing GUI with all settings
// ================================================================================
@ScriptManifest(
    name        = "UltimateDiceBot Pro",
    description = "Advanced dice betting bot with platinum token support, discord webhooks, and robust fallbacks",
    author      = "UltimateDiceBot",
    version     = 3.0,
    category    = Category.MISC
)
public class UltimateDiceBot extends AbstractScript {

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTANTS
    // ─────────────────────────────────────────────────────────────────────────
    private static final int    COIN_ID               = 995;
    private static final int    PLATINUM_TOKEN_ID     = 13204;
    private static final long   PLATINUM_VALUE        = 1_000L;   // 1 token = 1 000 coins
    private static final int    TRADE_RETRY_MAX       = 3;
    private static final long   TRADE_RETRY_DELAY_MS  = 1_000L;
    private static final long   BANK_RETRY_DELAY_MS   = 60_000L;
    private static final String    VERSION               = "3.0";

    // ─────────────────────────────────────────────────────────────────────────
    // STATE ENUM
    // ─────────────────────────────────────────────────────────────────────────
    private enum GameType { DICE, HOT_COLD, FLOWER_POKER }

    private enum BotState {
        STARTUP_GUI,
        CONFIGURATION_CHECK,
        MOVING_TO_LOCATION,
        WAITING_FOR_TRADE,
        TRADE_RECEIVED,
        PROCESSING_BET,
        PROCESSING_HOT_COLD,
        PAYING_WINNINGS,
        LOSS_OR_PAYOUT_COMPLETE,
        IDLE_BREAK,
        OUT_OF_FUNDS,
        ERROR_RECOVERY
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENUMS
    // ─────────────────────────────────────────────────────────────────────────
    private enum PayoutCurrency  { AUTO, COINS, PLATINUM_TOKENS }
    private enum BankrollSource  { INVENTORY_ONLY, BANK_AND_INVENTORY }

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIGURATION (POJO populated by GUI)
    // ─────────────────────────────────────────────────────────────────────────
    private static class BotConfig {
        /* ── Game Type ── */
        GameType gameType = GameType.DICE;

        /* ── Betting & Bankroll ── */
        BankrollSource bankrollSource     = BankrollSource.BANK_AND_INVENTORY;
        long   initialBankroll            = 1_000_000L;
        long   minBet                     = 1_000L;
        long   maxBet                     = 100_000L;
        double lowFundsThresholdPct       = 20.0;
        boolean antiBanEnabled            = true;

        /* ── Dice Mechanics ── */
        Map<Integer, Double> payoutTable  = new LinkedHashMap<>();
        boolean feeEnabled                = false;
        double  feeRate                   = 2.0;           // percent
        PayoutCurrency payoutCurrency     = PayoutCurrency.AUTO;

        /* ── Security ── */
        boolean antiLureEnabled           = true;
        int     tradeSpamLimit            = 5;             // per minute
        int     tradeCancellationLimit    = 3;
        long    blacklistDurationMs       = 5 * 60_000L;  // 5 minutes

        /* ── Discord ── */
        boolean discordEnabled            = false;
        String  discordWebhookUrl         = "";

        /* ── Auto-Chat ── */
        boolean autoChatEnabled           = false;
        String  autoChatMessage           = "Come try your luck at dice! 7x2, 9x4, 12x4! Hot/Cold 1-100! PM me to play!";
        long    autoChatIntervalMs        = 60_000L; // 1 minute


        /* ── Location ── */
        boolean useCustomLocation         = false;
        int     customX                   = 3202;
        int     customY                   = 3423;
        int     customPlane               = 0;

        BotConfig() {
            // Default payout table: sum → multiplier (0 = loss)
            payoutTable.put(2,   0.0);
            payoutTable.put(3,   0.0);
            payoutTable.put(4,   0.0);
            payoutTable.put(5,   0.0);
            payoutTable.put(6,   0.0);
            payoutTable.put(7,   2.0);  // 2× on sum 7
            payoutTable.put(8,   0.0);
            payoutTable.put(9,   4.0);  // 4× on sum 9
            payoutTable.put(10,  0.0);
            payoutTable.put(11,  0.0);
            payoutTable.put(12,  4.0);  // 4× on sum 12
        }

        /* ── Hot/Cold Settings ── */
        int hotColdMin = 1;
        int hotColdMax = 100;
        int hotColdMidLow = 48;
        int hotColdMidHigh = 52;
        double hotColdPayout = 2.0; // 2x for correct guess, 1x for tie
        String hotColdPlayerGuess = "HOT"; // Default guess for player, will be configurable in GUI

        /* ── Flower Poker Settings ── */
        // To be defined later based on API capabilities

    }

    // ─────────────────────────────────────────────────────────────────────────
    // DEBT RECORD
    // ─────────────────────────────────────────────────────────────────────────
    private static class DebtRecord {
        final String playerName;
        final long   amountCoins;
        final String timestamp;
        boolean      settled = false;

        DebtRecord(String player, long coins) {
            this.playerName  = player;
            this.amountCoins = coins;
            this.timestamp   = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(LocalDateTime.now());
        }

        @Override
        public String toString() {
            return String.format("[%s] %s owes %,d gp%s",
                timestamp, playerName, amountCoins, settled ? " (SETTLED)" : "");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIELDS
    // ─────────────────────────────────────────────────────────────────────────
    private BotState  currentState  = BotState.STARTUP_GUI;
    private BotConfig config        = new BotConfig();
    private ConfigGUI gui;
    private final Random random     = new Random();

    /** player-name (lower) → blacklist-expiry epoch-ms */
    private final ConcurrentHashMap<String, Long> blacklist = new ConcurrentHashMap<>();

    /** player-name (lower) → queue of trade-attempt timestamps */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> tradeAttempts =
        new ConcurrentHashMap<>();

    /** unpaid winnings */
    private final ConcurrentLinkedQueue<DebtRecord> debtLog = new ConcurrentLinkedQueue<>();

    /* ── current trade state ── */
    private String  currentTradePlayer  = null;
    private long    currentBetCoins     = 0L;
    private int     lastDiceSum         = 0;
    private boolean lastRollWin         = false;
    private long    lastPayoutCoins     = 0L;
    private String  currentHotColdGuess = null; // "HOT" or "COLD"

    /* ── session statistics ── */
    private long initialBankrollSnap    = 0L;
    private long sessionProfit          = 0L;
    private long totalBets              = 0L;
    private long totalWins              = 0L;
    private long startTime              = 0L;

    /* ── timing ── */
    private long lastAntiBanTime        = 0L;
    private long lastBreakTime          = 0L;
    private long lastAutoChatTime       = 0L;

    /* ── misc flags ── */
    private boolean guiLaunched         = false;

    /** Reference to the GUI's live status label */
    private JLabel statusLabel;

    // ─────────────────────────────────────────────────────────────────────────
    // SCRIPT LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onStart() {
        logMsg("UltimateDiceBot Pro v" + VERSION + " loading...");
        startTime    = System.currentTimeMillis();
        currentState = BotState.STARTUP_GUI;
    }

    @Override
    public int onLoop() {
        try {
            /* ── GUI startup ── */
            if (currentState == BotState.STARTUP_GUI) {
                if (!guiLaunched) {
                    guiLaunched = true;
                    SwingUtilities.invokeLater(() -> {
                        gui = new ConfigGUI();
                        gui.setVisible(true);
                    });
                }
                return 200;
            }

            /* ── Anti-ban ── */
            if (config.antiBanEnabled) runAntiBan();

            /* ── Random idle break ── */
            if (shouldTakeBreak()) { currentState = BotState.IDLE_BREAK; }

            /* ── Auto-chat ── */
            if (config.autoChatEnabled && (System.currentTimeMillis() - lastAutoChatTime > config.autoChatIntervalMs)) {
                sendAutoChatMessage();
                lastAutoChatTime = System.currentTimeMillis();
            }

            switch (currentState) {
                case CONFIGURATION_CHECK:   return handleConfigCheck();
                case MOVING_TO_LOCATION:    return handleMoving();
                case WAITING_FOR_TRADE:     return handleWaiting();
                case TRADE_RECEIVED:        return handleTradeReceived();
                case PROCESSING_BET:        return handleProcessingBet();
                case PROCESSING_HOT_COLD:   return handleProcessingHotCold();
                case PAYING_WINNINGS:       return handlePayingWinnings();
                case LOSS_OR_PAYOUT_COMPLETE: return handleLossOrComplete();
                case IDLE_BREAK:            return handleIdleBreak();
                case OUT_OF_FUNDS:          return handleOutOfFunds();
                case ERROR_RECOVERY:        return handleErrorRecovery();
                default:                    return 600;
            }
        } catch (Exception ex) {
            logMsg("UNHANDLED EXCEPTION in main loop: " + ex.getMessage());
            ex.printStackTrace();
            currentState = BotState.ERROR_RECOVERY;
            return 1_000;
        }
    }

    @Override
    public void onExit() {
        String summary = String.format(
            "**UltimateDiceBot Pro** session ended.%n" +
            "Runtime: %s | Profit: %s | Bets: %d | Wins: %d | Debts: %d",
            formatRuntime(), formatCoins(sessionProfit), totalBets, totalWins, debtLog.size());
        logMsg(summary.replace("**", "").replace("%n", "\n"));
        discordSend(summary);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STATE HANDLERS
    // ─────────────────────────────────────────────────────────────────────────

    private int handleProcessingHotCold() {
        updateStatus("Processing Hot/Cold bet from: " + currentTradePlayer);

        if (!Trade.isOpen()) {
            logMsg("Trade window closed unexpectedly during Hot/Cold.");
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }

        // The bet amount should already be set in currentBetCoins from handleProcessingBet
        if (currentBetCoins <= 0) {
            logMsg("Zero-value bet for Hot/Cold; declining.");
            Trade.declineTrade();
            currentState = BotState.WAITING_FOR_TRADE;
            return 500;
        }

        // Generate random number for Hot/Cold (1-100)
        int randomNumber = random.nextInt(100) + 1;
        boolean win = false;
        long rawPayout = 0L;

        // Hot/Cold logic: player guesses if number is hot (high) or cold (low)
        // For simplicity, let's assume the player bets on 'hot' (above midHigh) or 'cold' (below midLow)
        // This needs to be determined from the trade message or a pre-set config, for now, let's assume a simple win condition.
        // The current implementation of the bot doesn't parse player intent from trade messages.
        // For now, let's define a simple win condition based on the number falling outside the 'mid' range.

        // Example: If player bets on 'Hot' (number > config.hotColdMidHigh) or 'Cold' (number < config.hotColdMidLow)
        // This part needs more sophisticated parsing of player's intent from trade message, which is not in current scope.
        // For now, let's make a simple win condition: if the number is outside the middle range, it's a win.
        if (randomNumber < config.hotColdMidLow || randomNumber > config.hotColdMidHigh) {
            win = true;
            rawPayout = (long) (currentBetCoins * config.hotColdPayout);
        } else { // Tie or loss
            win = false;
            rawPayout = 0L; // Player loses bet
        }

        lastRollWin = win;
        lastPayoutCoins = rawPayout;

        logMsg(String.format("[%s] Hot/Cold Roll: %d → %s", currentTradePlayer, randomNumber, win ? "WIN" : "LOSS"));
        discordSend(String.format(
            win ? ":fire: **HOT/COLD WIN**" : ":snowflake: **HOT/COLD LOSS**",
            currentTradePlayer, formatCoins(currentBetCoins), randomNumber,
            win ? "Payout: " + formatCoins(lastPayoutCoins) : ""));

        // Accept first trade screen
        humanDelay();
        boolean firstAccepted = retryAction(() -> Trade.acceptTrade(), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        if (!firstAccepted) {
            logMsg("Failed to accept first trade screen for Hot/Cold.");
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }
        Sleep.sleepUntil(() -> Trade.canAccept(), 5_000);
        humanDelay();

        // Confirm trade (second screen)
        boolean confirmed = retryAction(() -> Trade.acceptTrade(), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        if (!confirmed) {
            logMsg("Failed to confirm trade (second screen) for Hot/Cold.");
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }
        Sleep.sleepUntil(() -> !Trade.isOpen(), 5_000);

        totalBets++;
        currentState = lastRollWin ? BotState.PAYING_WINNINGS : BotState.LOSS_OR_PAYOUT_COMPLETE;
        return 600;
    }

    private int handleConfigCheck() {
        updateStatus("Checking configuration...");
        long bankroll = getTotalBankrollCoins();
        if (bankroll < config.minBet) {
            logMsg("Bankroll (" + formatCoins(bankroll) + ") is below minimum bet. Entering OUT_OF_FUNDS.");
            currentState = BotState.OUT_OF_FUNDS;
            return 1_000;
        }
        initialBankrollSnap = bankroll;
        logMsg("Configuration OK. Bankroll: " + formatCoins(bankroll));
        currentState = config.useCustomLocation ? BotState.MOVING_TO_LOCATION : BotState.WAITING_FOR_TRADE;
        return 500;
    }

    private int handleMoving() {
        updateStatus("Walking to configured location...");
        Tile target = new Tile(config.customX, config.customY, config.customPlane);
        if (!Walking.shouldWalk(5)) {
            currentState = BotState.WAITING_FOR_TRADE;
            return 500;
        }
        Walking.walk(target);
        return 800;
    }

    private int handleWaiting() {
        updateStatus(String.format("Waiting | Bankroll: %s | Profit: %s | Debts: %d",
            formatCoins(getTotalBankrollCoins()), formatCoins(sessionProfit), debtLog.size()));
        expireBlacklist();

        String tradingWith = Trade.getTradingWith();
        if (tradingWith != null || Trade.isOpen()) {
            String name = tradingWith != null ? tradingWith : currentTradePlayer;
            if (name == null) {
                Player requester = getTradeRequester();
                if (requester != null) name = requester.getName();
            }
            
            if (name != null) {
                if (isBlacklisted(name)) {
                    logMsg("Ignoring blacklisted player: " + name);
                    Trade.declineTrade();
                    return 600;
                }
                if (isSpamming(name)) {
                    logMsg(name + " is trade-spamming. Adding to blacklist.");
                    blacklistPlayer(name);
                    Trade.declineTrade();
                    return 600;
                }
                currentTradePlayer = name;
                currentState       = BotState.TRADE_RECEIVED;
                logMsg("Trade detected with: " + name);
                return 200;
            }
        }
        return 600;
    }

    private void sendAutoChatMessage() {
        if (config.autoChatMessage != null && !config.autoChatMessage.isBlank()) {
            sendChatMessage(config.autoChatMessage);
        }
    }

    private int handleTradeReceived() {
        updateStatus("Trade received – accepting from: " + currentTradePlayer);

        boolean accepted = retryAction(() -> Trade.acceptTrade(), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        if (!accepted) {
            logMsg("Failed to accept trade request from " + currentTradePlayer);
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }
        Sleep.sleepUntil(() -> Trade.isOpen(), 6_000);
        if (!Trade.isOpen()) {
            logMsg("Trade window did not open.");
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }
        recordTradeAttempt(currentTradePlayer);
        currentState = BotState.PROCESSING_BET;
        return 400;
    }

    private int handleProcessingBet() {
        updateStatus("Processing bet from: " + currentTradePlayer);

        if (!Trade.isOpen()) {
            logMsg("Trade window closed unexpectedly.");
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }

        /* ── Wait up to 3 s for them to offer items ── */
        Sleep.sleepUntil(() -> {
            Item[] items = Trade.getTheirItems();
            return items != null && items.length > 0;
        }, 3_000);

        Item[] theirItems = Trade.getTheirItems();
        if (theirItems == null || theirItems.length == 0) {
            logMsg("No items offered by " + currentTradePlayer + ". Declining.");
            Trade.declineTrade();
            currentState = BotState.WAITING_FOR_TRADE;
            return 500;
        }

        /* ── Anti-lure: reject non-currency items ── */
        if (config.antiLureEnabled) {
            for (Item item : theirItems) {
                if (item == null) continue;
                if (item.getID() != COIN_ID && item.getID() != PLATINUM_TOKEN_ID) {
                    logMsg("Non-currency item from " + currentTradePlayer + " (ID=" + item.getID() + "). Declining.");
                    Trade.declineTrade();
                    currentState = BotState.WAITING_FOR_TRADE;
                    return 500;
                }
            }
        }

        /* ── Calculate bet value in coins ── */
        long betCoins = 0L;
        for (Item item : theirItems) {
            if (item == null) continue;
            if (item.getID() == COIN_ID)           betCoins += item.getAmount();
            else if (item.getID() == PLATINUM_TOKEN_ID) betCoins += (long) item.getAmount() * PLATINUM_VALUE;
        }

        if (betCoins <= 0) {
            logMsg("Zero-value bet from " + currentTradePlayer + ". Declining.");
            Trade.declineTrade();
            currentState = BotState.WAITING_FOR_TRADE;
            return 500;
        }

        /* ── Bet limit validation ── */
        long dynamicMax    = Math.max(1L, (long) (getTotalBankrollCoins() * 0.10));
        long effectiveMax  = Math.min(config.maxBet, dynamicMax);

        if (betCoins < config.minBet) {
            logMsg(String.format("Bet %s below min %s. Declining.", formatCoins(betCoins), formatCoins(config.minBet)));
            Trade.declineTrade();
            currentState = BotState.WAITING_FOR_TRADE;
            return 500;
        }
        if (betCoins > effectiveMax) {
            logMsg(String.format("Bet %s exceeds max %s. Declining.", formatCoins(betCoins), formatCoins(effectiveMax)));
            Trade.declineTrade();
            currentState = BotState.WAITING_FOR_TRADE;
            return 500;
        }

        currentBetCoins = betCoins;

        // Process game logic based on game type
        processGameLogic();

        /* ── Accept first trade screen ── */
        humanDelay();
        boolean firstAccepted = retryAction(() -> Trade.acceptTrade(), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        if (!firstAccepted) {
            logMsg("Failed to accept first trade screen.");
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }
        Sleep.sleepUntil(() -> Trade.canAccept(), 5_000);
        humanDelay();

        /* ── Confirm trade (second screen) ── */
        boolean confirmed = retryAction(() -> Trade.acceptTrade(), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        if (!confirmed) {
            logMsg("Failed to confirm trade (second screen).");
            currentState = BotState.ERROR_RECOVERY;
            return 500;
        }
        Sleep.sleepUntil(() -> !Trade.isOpen(), 5_000);

        totalBets++;
        currentState = lastRollWin ? BotState.PAYING_WINNINGS : BotState.LOSS_OR_PAYOUT_COMPLETE;
        return 600;
    }

    private void processGameLogic() {
        switch (config.gameType) {
            case DICE:
                processDiceGame();
                break;
            case HOT_COLD:
                processHotColdGame();
                break;
            case FLOWER_POKER:
                processFlowerPokerGame();
                break;
        }
    }

    private void processDiceGame() {
        /* ── Roll two six-sided dice ── */
        int die1       = random.nextInt(6) + 1;
        int die2       = random.nextInt(6) + 1;
        lastDiceSum    = die1 + die2;
        double mult    = config.payoutTable.getOrDefault(lastDiceSum, 0.0);
        lastRollWin    = (mult > 0);

        if (lastRollWin) {
            long raw = (long) (currentBetCoins * mult);
            if (config.feeEnabled && config.feeRate > 0) {
                raw -= (long) (raw * config.feeRate / 100.0);
            }
            lastPayoutCoins = raw;
            logMsg(String.format("[%s] Roll %d+%d=%d → WIN  Payout: %s",
                currentTradePlayer, die1, die2, lastDiceSum, formatCoins(lastPayoutCoins)));
            discordSend(String.format(
                ":game_die: **WIN** | `%s` | Bet: %s | Roll: %d+%d=%d | Payout: %s",
                currentTradePlayer, formatCoins(currentBetCoins), die1, die2, lastDiceSum, formatCoins(lastPayoutCoins)));
        } else {
            lastPayoutCoins = 0L;
            logMsg(String.format("[%s] Roll %d+%d=%d → LOSS  Collected: %s",
                currentTradePlayer, die1, die2, lastDiceSum, formatCoins(currentBetCoins)));
            discordSend(String.format(
                ":game_die: **LOSS** | `%s` | Bet: %s | Roll: %d+%d=%d",
                currentTradePlayer, formatCoins(currentBetCoins), die1, die2, lastDiceSum));
        }
    }

    private void processHotColdGame() {
        // Generate random number for Hot/Cold (1-100)
        int randomNumber = random.nextInt(100) + 1;
        boolean win = false;
        long rawPayout = 0L;

        // Determine win/loss based on player's guess (config.hotColdPlayerGuess)
        // For simplicity, assuming player states their guess in trade message, which is not parsed yet.
        // For now, let's use a simple condition: if the number is outside the middle range, it's a win.
        if (config.hotColdPlayerGuess.equalsIgnoreCase("HOT") && randomNumber > config.hotColdMidHigh) {
            win = true;
            rawPayout = (long) (currentBetCoins * config.hotColdPayout);
        } else if (config.hotColdPlayerGuess.equalsIgnoreCase("COLD") && randomNumber < config.hotColdMidLow) {
            win = true;
            rawPayout = (long) (currentBetCoins * config.hotColdPayout);
        } else {
            win = false;
            rawPayout = 0L;
        }

        lastRollWin = win;
        lastPayoutCoins = rawPayout;

        logMsg(String.format("[%s] Hot/Cold Roll: %d (Guess: %s) → %s", currentTradePlayer, randomNumber, config.hotColdPlayerGuess, win ? "WIN" : "LOSS"));
        discordSend(String.format(
            win ? ":fire: **HOT/COLD WIN**" : ":snowflake: **HOT/COLD LOSS**",
            currentTradePlayer, formatCoins(currentBetCoins), randomNumber, config.hotColdPlayerGuess,
            win ? "Payout: " + formatCoins(lastPayoutCoins) : ""));
    }

    private void processFlowerPokerGame() {
        // Placeholder for Flower Poker logic
        logMsg("Flower Poker game logic not yet implemented.");
        lastRollWin = false; // Default to loss for unimplemented game
        lastPayoutCoins = 0L;
    }
    }

    private int handlePayingWinnings() {
        updateStatus(String.format("Paying %s to %s", formatCoins(lastPayoutCoins), currentTradePlayer));

        /* ── Check sufficient funds; attempt bank top-up ── */
        if (getTotalBankrollCoins() < lastPayoutCoins) {
            if (config.bankrollSource == BankrollSource.BANK_AND_INVENTORY) {
                logMsg("Insufficient funds. Attempting bank reload...");
                if (!loadFromBank(lastPayoutCoins)) {
                    return recordDebtAndContinue();
                }
            } else {
                return recordDebtAndContinue();
            }
        }

        /* ── Locate the winning player ── */
        Player winner = Players.closest(currentTradePlayer);
        if (winner == null) {
            logMsg("Winner " + currentTradePlayer + " not nearby. Recording debt.");
            return recordDebtAndContinue();
        }

        /* ── Initiate payment trade ── */
        boolean traded = retryAction(() -> winner.interact("Trade with"), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        if (!traded) {
            logMsg("Could not initiate payment trade. Recording debt.");
            return recordDebtAndContinue();
        }

        Sleep.sleepUntil(() -> Trade.isOpen(), 8_000);
        if (!Trade.isOpen()) {
            logMsg("Payment trade window did not open. Recording debt.");
            return recordDebtAndContinue();
        }

        humanDelay();

        /* ── Offer payout (coins / tokens / mixed) with fallback ── */
        if (!offerPayment(lastPayoutCoins)) {
            logMsg("Payment offer failed. Recording debt.");
            Trade.declineTrade();
            return recordDebtAndContinue();
        }

        humanDelay();

        /* ── Accept first payment screen ── */
        if (!retryAction(() -> Trade.acceptTrade(), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS)) {
            logMsg("Failed to accept payment first screen. Recording debt.");
            return recordDebtAndContinue();
        }
        Sleep.sleepUntil(() -> Trade.canAccept(), 5_000);
        humanDelay();

        /* ── Confirm payment ── */
        if (!retryAction(() -> Trade.acceptTrade(), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS)) {
            logMsg("Failed to confirm payment trade. Recording debt.");
            return recordDebtAndContinue();
        }
        Sleep.sleepUntil(() -> !Trade.isOpen(), 5_000);

        totalWins++;
        /* net profit: we collected bet, we paid out payout */
        sessionProfit += (currentBetCoins - lastPayoutCoins);
        logMsg("Payment complete → " + currentTradePlayer + " | " + formatCoins(lastPayoutCoins));
        currentState = BotState.LOSS_OR_PAYOUT_COMPLETE;
        return 500;
    }

    private int handleLossOrComplete() {
        updateStatus("Trade complete. Ready for next bet.");
        sessionProfit += currentBetCoins;   // loss: we keep the bet
        totalBets++;
        resetTradeState();
        checkLowFunds();
        currentState = BotState.WAITING_FOR_TRADE;
        return 800;
    }

    private int handleIdleBreak() {
        int ms = 3_000 + random.nextInt(4_000);
        updateStatus("Idle break for " + ms / 1_000 + " s...");
        sleep(ms);
        lastBreakTime = System.currentTimeMillis();
        currentState  = BotState.WAITING_FOR_TRADE;
        return 200;
    }

    private int handleOutOfFunds() {
        updateStatus("OUT OF FUNDS – retrying in 60 s | Debts: " + debtLog.size());
        if (config.bankrollSource == BankrollSource.BANK_AND_INVENTORY) {
            logMsg("Out of funds. Waiting " + (BANK_RETRY_DELAY_MS / 1_000) + " s before bank retry.");
            discordSend(":warning: **Out of Funds** | Bot paused. Attempting bank reload. Debts: " + debtLog.size());
            sleep((int) BANK_RETRY_DELAY_MS);
            if (loadFromBank(config.minBet * 20)) {
                logMsg("Funds reloaded from bank. Resuming.");
                currentState = BotState.WAITING_FOR_TRADE;
            } else {
                logMsg("Bank reload failed. Will retry next cycle.");
                sendChatMessage("Temporarily out of funds. Please check back shortly.");
            }
        } else {
            logMsg("Inventory-only mode and out of funds. Stopping script.");
            sendChatMessage("Bot out of funds. Closing session.");
            stop();
        }
        return 2_000;
    }

    private int handleErrorRecovery() {
        updateStatus("Error recovery...");
        try {
            if (Trade.isOpen()) {
                Trade.declineTrade();
                Sleep.sleepUntil(() -> !Trade.isOpen(), 3_000);
            }
        } catch (Exception ignored) {}
        resetTradeState();
        sleep(1_500);
        logMsg("Recovery complete. Returning to WAITING_FOR_TRADE.");
        currentState = BotState.WAITING_FOR_TRADE;
        return 500;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAYMENT HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Offer payout amount using preferred currency with automatic fallback:
     *   1. Preferred currency → 2. Alternate currency → 3. Mixed → false (record debt)
     */
    private boolean offerPayment(long totalCoins) {
        long invCoins  = Inventory.count(COIN_ID);
        long invTokens = Inventory.count(PLATINUM_TOKEN_ID);
        long tokenVal  = invTokens * PLATINUM_VALUE;

        long tokensNeeded   = totalCoins / PLATINUM_VALUE;
        long coinRemainder  = totalCoins % PLATINUM_VALUE;

        switch (config.payoutCurrency) {
            case COINS:
                if (invCoins >= totalCoins)
                    return offerCoinsOnly(totalCoins);
                // Fallback: use tokens + coins
                logMsg("Insufficient coins, falling back to tokens for payout.");
                if (invTokens + invCoins * PLATINUM_VALUE / PLATINUM_VALUE >= tokensNeeded
                        && tokenVal + invCoins >= totalCoins)
                    return offerMixed(totalCoins, invCoins, invTokens);
                return false;

            case PLATINUM_TOKENS:
                if (invTokens >= tokensNeeded && invCoins >= coinRemainder)
                    return offerTokensAndCoins(tokensNeeded, coinRemainder);
                // Fallback: coins
                logMsg("Insufficient tokens, falling back to coins for payout.");
                if (invCoins >= totalCoins)
                    return offerCoinsOnly(totalCoins);
                if (tokenVal + invCoins >= totalCoins)
                    return offerMixed(totalCoins, invCoins, invTokens);
                return false;

            default: // AUTO – minimise inventory slots
                if (invTokens >= tokensNeeded && invCoins >= coinRemainder)
                    return offerTokensAndCoins(tokensNeeded, coinRemainder);
                if (invCoins >= totalCoins)
                    return offerCoinsOnly(totalCoins);
                if (tokenVal + invCoins >= totalCoins)
                    return offerMixed(totalCoins, invCoins, invTokens);
                return false;
        }
    }

    private boolean offerCoinsOnly(long amount) {
        return retryAction(() -> Trade.addItem(COIN_ID, (int) Math.min(amount, Integer.MAX_VALUE)),
            TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
    }

    private boolean offerTokensAndCoins(long tokens, long coins) {
        boolean ok = true;
        if (tokens > 0)
            ok = retryAction(() -> Trade.addItem(PLATINUM_TOKEN_ID, (int) tokens), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        if (ok && coins > 0)
            ok = retryAction(() -> Trade.addItem(COIN_ID, (int) coins), TRADE_RETRY_MAX, TRADE_RETRY_DELAY_MS);
        return ok;
    }

    private boolean offerMixed(long totalCoins, long availCoins, long availTokens) {
        long maxTokens = Math.min(availTokens, totalCoins / PLATINUM_VALUE);
        long remaining = totalCoins - (maxTokens * PLATINUM_VALUE);
        if (remaining > availCoins) return false;
        return offerTokensAndCoins(maxTokens, remaining);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BANKROLL HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns total value (coins + tokens converted) from inventory */
    private long getTotalBankrollCoins() {
        return Inventory.count(COIN_ID) +
               Inventory.count(PLATINUM_TOKEN_ID) * PLATINUM_VALUE;
    }

    /**
     * Attempts to open bank and withdraw enough funds.
     * Will retry opening up to twice; on hard failure returns false.
     */
    private boolean loadFromBank(long neededCoins) {
        try {
            if (!Bank.isOpen()) {
                if (!Bank.open()) { sleep(2_000); Bank.open(); }
            }
            Sleep.sleepUntil(() -> Bank.isOpen(), 6_000);
            if (!Bank.isOpen()) return false;

            long bankCoins  = Bank.count(COIN_ID);
            long bankTokens = Bank.count(PLATINUM_TOKEN_ID);
            long bankTotal  = bankCoins + bankTokens * PLATINUM_VALUE;

            if (bankTotal < neededCoins) { Bank.close(); return false; }

            if (config.payoutCurrency == PayoutCurrency.PLATINUM_TOKENS
             || config.payoutCurrency == PayoutCurrency.AUTO) {
                long take = Math.min(bankTokens, neededCoins / PLATINUM_VALUE + 1);
                if (take > 0) { Bank.withdraw(PLATINUM_TOKEN_ID, (int) take); sleep(400); }
                long rem = neededCoins - (take * PLATINUM_VALUE);
                if (rem > 0 && bankCoins > 0) {
                    Bank.withdraw(COIN_ID, (int) Math.min(bankCoins, rem + config.minBet * 5));
                    sleep(400);
                }
            } else {
                long take = Math.min(bankCoins, neededCoins + config.minBet * 5);
                Bank.withdraw(COIN_ID, (int) take);
                sleep(400);
            }
            Bank.close();
            Sleep.sleepUntil(() -> !Bank.isOpen(), 3_000);
            return true;
        } catch (Exception ex) {
            logMsg("Bank load exception: " + ex.getMessage());
            return false;
        }
    }

    private void checkLowFunds() {
        if (initialBankrollSnap <= 0) return;
        long   current = getTotalBankrollCoins();
        double pct     = (double) current / initialBankrollSnap * 100.0;
        if (pct < config.lowFundsThresholdPct) {
            String msg = String.format("LOW FUNDS: %.1f%% of initial bankroll remaining (%s)",
                pct, formatCoins(current));
            logMsg(msg);
            discordSend(":warning: **Low Funds** | " + msg);
            if (config.bankrollSource == BankrollSource.BANK_AND_INVENTORY)
                loadFromBank(config.minBet * 20);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLACKLIST & SPAM DETECTION
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isBlacklisted(String name) {
        Long expiry = blacklist.get(name.toLowerCase());
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) { blacklist.remove(name.toLowerCase()); return false; }
        return true;
    }

    private void blacklistPlayer(String name) {
        long exp = System.currentTimeMillis() + config.blacklistDurationMs;
        blacklist.put(name.toLowerCase(), exp);
        logMsg("Blacklisted: " + name + " for " + config.blacklistDurationMs / 60_000 + " min");
        discordSend(":no_entry: **Blacklisted** player `" + name + "` for " + config.blacklistDurationMs / 60_000 + " min");
    }

    private void expireBlacklist() {
        long now = System.currentTimeMillis();
        blacklist.entrySet().removeIf(e -> e.getValue() < now);
    }

    private boolean isSpamming(String name) {
        ConcurrentLinkedQueue<Long> q = tradeAttempts.computeIfAbsent(
            name.toLowerCase(), k -> new ConcurrentLinkedQueue<>());
        long now = System.currentTimeMillis();
        q.removeIf(t -> now - t > 60_000);
        return q.size() >= config.tradeSpamLimit;
    }

    private void recordTradeAttempt(String name) {
        tradeAttempts.computeIfAbsent(name.toLowerCase(), k -> new ConcurrentLinkedQueue<>())
                     .add(System.currentTimeMillis());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DEBT LOGGING
    // ─────────────────────────────────────────────────────────────────────────

    private void recordDebt(String player, long coins) {
        DebtRecord debt = new DebtRecord(player, coins);
        debtLog.add(debt);
        logMsg("DEBT RECORDED: " + debt);
        sendChatMessage("@" + player + " Insufficient funds for automatic payout. Manual payout will be arranged.");
        discordSend(":ledger: **Debt Recorded** | Player: `" + player + "` | Amount: " + formatCoins(coins));
    }

    /** Helper: record debt and transition to LOSS_OR_PAYOUT_COMPLETE */
    private int recordDebtAndContinue() {
        recordDebt(currentTradePlayer, lastPayoutCoins);
        currentState = BotState.LOSS_OR_PAYOUT_COMPLETE;
        return 500;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ANTI-BAN
    // ─────────────────────────────────────────────────────────────────────────

    private void runAntiBan() {
        long now = System.currentTimeMillis();
        if (now - lastAntiBanTime < 5_000 + random.nextInt(10_000)) return;
        lastAntiBanTime = now;
        switch (random.nextInt(4)) {
            case 0: Camera.rotateTo(random.nextInt(360), 60 + random.nextInt(30)); break;
            case 1:
                Camera.rotateTo(
                    (Camera.getYaw() + random.nextInt(80) - 40 + 360) % 360,
                    Camera.getPitch());
                break;
            case 2:
                Tabs.open(Tab.SKILLS);
                sleep(200 + random.nextInt(400));
                Tabs.open(Tab.INVENTORY);
                break;
            case 3: sleep(100 + random.nextInt(300)); break;
        }
    }

    private boolean shouldTakeBreak() {
        return System.currentTimeMillis() - lastBreakTime > 30_000 && random.nextInt(100) < 2;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITY HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Retry a boolean-returning action up to maxTries times with delayMs between attempts */
    @FunctionalInterface
    private interface BooleanAction { boolean run(); }

    private boolean retryAction(BooleanAction action, int maxTries, long delayMs) {
        for (int i = 0; i < maxTries; i++) {
            if (action.run()) return true;
            sleep((int) delayMs);
        }
        return false;
    }

    private Player getTradeRequester() {
        Player local = Players.getLocal();
        // Look for a nearby player who is interacting with us
        for (Player p : Players.all()) {
            if (p == null || p.equals(local)) continue;
            if (p.isInteracting(local)) return p;
        }
        // Fallback: nearest non-local player
        return Players.closest(p -> p != null && !p.equals(Players.getLocal()));
    }

    private void resetTradeState() {
        currentTradePlayer = null;
        currentBetCoins    = 0L;
        lastPayoutCoins    = 0L;
        lastRollWin        = false;
    }

    private void humanDelay() { sleep(100 + random.nextInt(300)); }

    private void sendChatMessage(String message) {
        try {
            Keyboard.type(message, true);
        } catch (Exception ex) {
            logMsg("Chat message failed: " + ex.getMessage());
        }
    }

    private void discordSend(String message) {
        if (!config.discordEnabled || config.discordWebhookUrl == null || config.discordWebhookUrl.isBlank()) return;
        new Thread(() -> {
            try {
                URL url = new URL(config.discordWebhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                String ts   = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now());
                String json = "{\"content\":\"[" + ts + "] " + jsonEscape(message) + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code != 204 && code != 200) logMsg("Discord HTTP " + code);
                conn.disconnect();
            } catch (Exception ex) {
                logMsg("Discord error: " + ex.getMessage());
            }
        }, "discord-webhook").start();
    }

    private void logMsg(String msg) {
        String ts = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now());
        Logger.log("[" + ts + "] UltimateDiceBot: " + msg);
    }

    private void updateStatus(String s) {
        if (statusLabel != null) SwingUtilities.invokeLater(() -> statusLabel.setText(" " + s));
    }

    private String formatCoins(long coins) {
        if (coins >= 1_000_000) return String.format("%.2fM gp", coins / 1_000_000.0);
        if (coins >= 1_000)     return String.format("%.1fK gp", coins / 1_000.0);
        return coins + " gp";
    }

    private String formatRuntime() {
        long ms  = System.currentTimeMillis() - startTime;
        long h   = ms / 3_600_000;
        long m   = (ms % 3_600_000) / 60_000;
        long s   = (ms % 60_000) / 1_000;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIGURATION GUI
    // ─────────────────────────────────────────────────────────────────────────
    private class ConfigGUI extends JFrame {

        /* ── Tab 1 fields ── */
        private JComboBox<String> bankrollSourceCombo;
        private JTextField initialBankrollField, minBetField, maxBetField, lowFundsPctField;
        private JCheckBox  antiBanCheck;

        /* ── Tab 2 fields ── */
        private DefaultTableModel payoutTableModel;
        private JCheckBox  feeEnabledCheck;
        private JTextField feeRateField;
        private JComboBox<String> payoutCurrencyCombo;

        /* ── Tab 3 fields ── */
        private JCheckBox  antiLureCheck;
        private JTextField tradeSpamField, tradeCancelField, blacklistDurationField;
        private JTextArea  blacklistArea;

        /* ── Tab 4 fields ── */
        private JCheckBox  discordEnabledCheck;
        private JTextField discordUrlField;

        /* ── Location ── */
        private JCheckBox  useLocationCheck;
        private JTextField locXField, locYField, locPlaneField;

        /* ── Status bar ── */
        private JLabel statusBar;

        ConfigGUI() {
            super("UltimateDiceBot Pro v" + VERSION + " – Configuration");
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(640, 560);
            setLocationRelativeTo(null);
            setResizable(false);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Betting & Bankroll", buildBettingTab());
            tabs.addTab("Dice Mechanics",     buildDiceTab());
            tabs.addTab("Security",           buildSecurityTab());
            tabs.addTab("Discord",            buildDiscordTab());

            /* ── Status bar ── */
            statusBar = new JLabel(" Ready to start...");
            statusBar.setBorder(BorderFactory.createEtchedBorder());
            statusBar.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            statusBar.setForeground(new Color(0, 100, 0));
            statusLabel = statusBar;

            /* ── Start button ── */
            JButton startBtn = new JButton("  Start UltimateDiceBot Pro  ");
            startBtn.setBackground(new Color(30, 130, 30));
            startBtn.setForeground(Color.WHITE);
            startBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            startBtn.setFocusPainted(false);
            startBtn.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
            startBtn.addActionListener(e -> onStartBot());

            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
            btnPanel.add(startBtn);

            JPanel southPanel = new JPanel(new BorderLayout());
            southPanel.add(btnPanel,   BorderLayout.NORTH);
            southPanel.add(statusBar,  BorderLayout.SOUTH);

            setLayout(new BorderLayout());
            add(tabs,       BorderLayout.CENTER);
            add(southPanel, BorderLayout.SOUTH);
        }

        // ── Tab 1: Betting & Bankroll ──────────────────────────────────────
        private JPanel buildBettingTab() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            GridBagConstraints c = gbc();
            int row = 0;

            addRow(p, c, row++, "Bankroll Source:",
                bankrollSourceCombo = new JComboBox<>(new String[]{"Bank & Inventory", "Inventory Only"}));
            addRow(p, c, row++, "Initial Bankroll (coins):",
                initialBankrollField = tf("1000000"));
            addRow(p, c, row++, "Minimum Bet (coins):",
                minBetField = tf("1000"));
            addRow(p, c, row++, "Maximum Bet (coins):",
                maxBetField = tf("100000"));
            addRow(p, c, row++, "Low Funds Threshold (%):",
                lowFundsPctField = tf("20"));

            antiBanCheck = new JCheckBox("Enable Anti-Ban (camera, mouse, breaks)", true);
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            p.add(antiBanCheck, c);
            c.gridwidth = 1;

            useLocationCheck = new JCheckBox("Walk to custom location on start", false);
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            p.add(useLocationCheck, c);
            c.gridwidth = 1;

            addRow(p, c, row++, "Custom X:", locXField = tf("3202"));
            addRow(p, c, row++, "Custom Y:", locYField = tf("3423"));
            addRow(p, c, row,   "Plane:",    locPlaneField = tf("0"));

            c.gridx = 0; c.gridy = ++row; c.gridwidth = 2;
            p.add(note("Dynamic max bet = min(maxBet, 10% of current bankroll)"), c);

            return p;
        }

        // ── Tab 2: Dice Mechanics ──────────────────────────────────────────
        private JPanel buildDiceTab() {
            JPanel p = new JPanel(new BorderLayout(6, 6));
            p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

            /* Payout table */
            String[] cols = {"Sum (2–12)", "Multiplier  (0 = house wins)"};
            payoutTableModel = new DefaultTableModel(cols, 0) {
                public boolean isCellEditable(int r, int c) { return c == 1; }
            };
            for (Map.Entry<Integer, Double> e : config.payoutTable.entrySet())
                payoutTableModel.addRow(new Object[]{e.getKey(), e.getValue()});

            JTable table = new JTable(payoutTableModel);
            table.setRowHeight(22);
            table.getColumnModel().getColumn(0).setPreferredWidth(110);
            table.getColumnModel().getColumn(1).setPreferredWidth(200);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createTitledBorder("Payout Table  (double-click multiplier to edit)"));

            /* Bottom settings */
            JPanel bot = new JPanel(new GridBagLayout());
            bot.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
            GridBagConstraints c = gbc();
            int row = 0;

            feeEnabledCheck = new JCheckBox("Enable Fee System", false);
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            bot.add(feeEnabledCheck, c);
            c.gridwidth = 1;

            addRow(bot, c, row++, "Fee Rate (%):", feeRateField = tf("2.0"));
            addRow(bot, c, row,   "Preferred Payout Currency:",
                payoutCurrencyCombo = new JComboBox<>(new String[]{"Auto", "Coins", "Platinum Tokens"}));

            p.add(scroll, BorderLayout.CENTER);
            p.add(bot,    BorderLayout.SOUTH);
            return p;
        }

        // ── Tab 3: Security & Anti-Abuse ──────────────────────────────────
        private JPanel buildSecurityTab() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            GridBagConstraints c = gbc();
            int row = 0;

            antiLureCheck = new JCheckBox("Anti-Lure: reject all non-currency items", true);
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            p.add(antiLureCheck, c);
            c.gridwidth = 1;

            addRow(p, c, row++, "Trade Spam Limit (per minute):", tradeSpamField = tf("5"));
            addRow(p, c, row++, "Trade Cancellation Limit:",      tradeCancelField = tf("3"));
            addRow(p, c, row++, "Blacklist Duration (minutes):",  blacklistDurationField = tf("5"));

            /* Blacklist viewer */
            blacklistArea = new JTextArea(5, 30);
            blacklistArea.setEditable(false);
            blacklistArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            blacklistArea.setText("(empty)");
            JScrollPane blScroll = new JScrollPane(blacklistArea);
            blScroll.setBorder(BorderFactory.createTitledBorder("Active Blacklist"));

            JButton refreshBtn = new JButton("Refresh");
            JButton clearBtn   = new JButton("Clear All");
            refreshBtn.addActionListener(e -> refreshBlacklist());
            clearBtn.addActionListener(e -> { blacklist.clear(); blacklistArea.setText("(empty)"); });

            JPanel blBtn = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            blBtn.add(refreshBtn);
            blBtn.add(clearBtn);

            c.gridx = 0; c.gridy = row++;   c.gridwidth = 2;
            p.add(blBtn,    c);
            c.gridy = row; c.fill = GridBagConstraints.BOTH; c.weightx = 1; c.weighty = 1;
            p.add(blScroll, c);
            return p;
        }

        // ── Tab 4: Discord Webhooks ────────────────────────────────────────
        private JPanel buildDiscordTab() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            GridBagConstraints c = gbc();
            int row = 0;

            discordEnabledCheck = new JCheckBox("Enable Discord Notifications", false);
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            p.add(discordEnabledCheck, c);
            c.gridwidth = 1;

            c.gridx = 0; c.gridy = row;   c.weightx = 0;
            p.add(new JLabel("Webhook URL:"), c);
            discordUrlField = new JTextField("https://discord.com/api/webhooks/YOUR_ID/YOUR_TOKEN", 35);
            c.gridx = 1; c.weightx = 1;
            p.add(discordUrlField, c);
            row++;

            JButton testBtn = new JButton("Send Test Message");
            testBtn.addActionListener(e -> {
                config.discordEnabled      = true;
                config.discordWebhookUrl   = discordUrlField.getText().trim();
                discordSend("**UltimateDiceBot Pro** test message. Webhook is working!");
                JOptionPane.showMessageDialog(this,
                    "Test message sent – check your Discord channel.", "Test", JOptionPane.INFORMATION_MESSAGE);
            });
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2; c.weightx = 0;
            p.add(testBtn, c);

            c.gridy = row++;
            p.add(note("<html>Notified events: wins, losses, debts, low funds, blacklist, session end</html>"), c);

            c.gridy = row;
            p.add(note("<html>Tip: Use a Discord channel webhook URL from Server Settings → Integrations</html>"), c);

            return p;
        }

        // ── START ACTION ──────────────────────────────────────────────────
        private void onStartBot() {
            try {
                config.bankrollSource       = bankrollSourceCombo.getSelectedIndex() == 0
                                              ? BankrollSource.BANK_AND_INVENTORY
                                              : BankrollSource.INVENTORY_ONLY;
                config.initialBankroll      = parseLong(initialBankrollField, "Initial Bankroll");
                config.minBet               = parseLong(minBetField, "Min Bet");
                config.maxBet               = parseLong(maxBetField, "Max Bet");
                config.lowFundsThresholdPct = parseDouble(lowFundsPctField, "Low Funds Threshold");
                config.antiBanEnabled       = antiBanCheck.isSelected();
                config.useCustomLocation    = useLocationCheck.isSelected();
                config.customX              = (int) parseLong(locXField,     "Custom X");
                config.customY              = (int) parseLong(locYField,     "Custom Y");
                config.customPlane          = (int) parseLong(locPlaneField, "Plane");

                /* Payout table */
                config.payoutTable.clear();
                for (int i = 0; i < payoutTableModel.getRowCount(); i++) {
                    int    sum  = Integer.parseInt(payoutTableModel.getValueAt(i, 0).toString().trim());
                    double mult = Double.parseDouble(payoutTableModel.getValueAt(i, 1).toString().trim());
                    config.payoutTable.put(sum, mult);
                }
                config.feeEnabled      = feeEnabledCheck.isSelected();
                config.feeRate         = parseDouble(feeRateField, "Fee Rate");
                config.payoutCurrency  = PayoutCurrency.values()[payoutCurrencyCombo.getSelectedIndex()];
                config.antiLureEnabled = antiLureCheck.isSelected();
                config.tradeSpamLimit  = (int) parseLong(tradeSpamField,           "Trade Spam Limit");
                config.tradeCancellationLimit = (int) parseLong(tradeCancelField,  "Cancellation Limit");
                config.blacklistDurationMs    = parseLong(blacklistDurationField,  "Blacklist Duration") * 60_000L;
                config.discordEnabled         = discordEnabledCheck.isSelected();
                config.discordWebhookUrl      = discordUrlField.getText().trim();

                /* Validation */
                if (config.minBet <= 0)                  throw new IllegalArgumentException("Min bet must be > 0");
                if (config.maxBet < config.minBet)       throw new IllegalArgumentException("Max bet must be >= min bet");
                if (config.lowFundsThresholdPct < 0 || config.lowFundsThresholdPct > 100)
                    throw new IllegalArgumentException("Low funds threshold must be 0–100");
                if (config.discordEnabled) {
                    if (!config.discordWebhookUrl.startsWith("https://discord.com/api/webhooks/"))
                        throw new IllegalArgumentException("Invalid Discord webhook URL");
                }

                logMsg("GUI configuration saved. Starting bot...");
                statusBar.setText(" Starting...");
                currentState = BotState.CONFIGURATION_CHECK;
                setVisible(false);

            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "Validation error: " + ex.getMessage(),
                    "Configuration Error", JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void refreshBlacklist() {
            if (blacklist.isEmpty()) { blacklistArea.setText("(empty)"); return; }
            StringBuilder sb = new StringBuilder();
            long now = System.currentTimeMillis();
            blacklist.forEach((name, exp) -> sb.append(name)
                .append("  (expires in ").append((exp - now) / 1_000).append("s)\n"));
            blacklistArea.setText(sb.toString().trim());
        }

        // ── GUI builder helpers ────────────────────────────────────────────
        private GridBagConstraints gbc() {
            GridBagConstraints c = new GridBagConstraints();
            c.insets  = new Insets(4, 5, 4, 5);
            c.anchor  = GridBagConstraints.WEST;
            c.fill    = GridBagConstraints.HORIZONTAL;
            c.weightx = 0;
            return c;
        }

        private void addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field) {
            c.gridx = 0; c.gridy = row; c.weightx = 0; p.add(new JLabel(label), c);
            c.gridx = 1;               c.weightx = 1; p.add(field, c);
        }

        private JTextField tf(String defVal) {
            JTextField f = new JTextField(defVal, 14);
            f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            return f;
        }

        private JLabel note(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(Color.GRAY);
            l.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
            return l;
        }

        private long parseLong(JTextField f, String name) {
            try { return Long.parseLong(f.getText().trim()); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " must be a whole number"); }
        }

        private double parseDouble(JTextField f, String name) {
            try { return Double.parseDouble(f.getText().trim()); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " must be a decimal number"); }
        }
    }
}
