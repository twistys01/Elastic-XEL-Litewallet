/******************************************************************************
 * Copyright © 2013-2016 The Nxt Core Developers.                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package nxt;

import nxt.db.DbIterator;
import nxt.crypto.Crypto;
import nxt.util.Listener;
import nxt.util.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

/**
 * Monitor account status based on account properties
 *
 * NXT, ASSET and CURRENCY balances can be monitored.  If a balance falls below the threshold, a transaction
 * will be submitted to transfer units from the funding account to the monitored account.  A transfer will
 * remain pending if the number of blocks since the previous transfer transaction is less than the monitor
 * interval.
 */
public final class AccountMonitor {

    /** Account monitor types */
    public enum MonitorType {
        NXT, ASSET, CURRENCY
    }

    /** Minimum monitor amount */
    public static final long MIN_FUND_AMOUNT = 1;

    /** Minimum monitor threshold */
    public static final long MIN_FUND_THRESHOLD = 1;

    /** Minimum funding interval */
    public static final int MIN_FUND_INTERVAL = 10;

    /** Maximum number of monitors */
    private static final int MAX_MONITORS = Nxt.getIntProperty("nxt.maxNumberOfMonitors");

    /** Monitor started */
    private static volatile boolean started = false;

    /** Monitor stopped */
    private static volatile boolean stopped = false;

    /** Active monitors */
    private static final List<AccountMonitor> monitors = new ArrayList<>();

    /** Monitored accounts */
    private static final Map<Long, List<MonitoredAccount>> accounts = new HashMap<>();

    /** Process semaphore */
    private static final Semaphore processSemaphore = new Semaphore(0);

    /** Pending updates */
    private static final ConcurrentLinkedQueue<MonitoredAccount> pendingEvents = new ConcurrentLinkedQueue<>();

    /** Account monitor type */
    private final MonitorType monitorType;

    /** Holding identifier */
    private final long holdingId;

    /** Account property */
    private final String property;

    /** Fund amount */
    private final long amount;

    /** Fund threshold */
    private final long threshold;

    /** Fund interval */
    private final int interval;

    /** Fund account identifier */
    private final long accountId;

    /** Fund account name */
    private final String accountName;

    /** Fund account secret phrase */
    private final String secretPhrase;

    /**
     * Create an account monitor
     *
     * @param   monitorType         Monitor type
     * @param   holdingId           Asset or Currency identifier, ignored for NXT monitor
     * @param   property            Account property name
     * @param   amount              Fund amount
     * @param   threshold           Fund threshold
     * @param   interval            Fund interval
     * @param   accountId           Fund account identifier
     * @param   secretPhrase        Fund account secret phrase
     */
    private AccountMonitor(MonitorType monitorType, long holdingId, String property,
                                    long amount, long threshold, int interval,
                                    long accountId, String secretPhrase) {
        this.monitorType = monitorType;
        this.holdingId = (monitorType != MonitorType.NXT ? holdingId : 0);
        this.property = property;
        this.amount = amount;
        this.threshold = threshold;
        this.interval = interval;
        this.accountId = accountId;
        this.accountName = Crypto.rsEncode(accountId);
        this.secretPhrase = secretPhrase;
    }

    /**
     * Return the monitor type
     *
     * @return                      Monitor type
     */
    public MonitorType getType() {
        return monitorType;
    }

    /**
     * Return the holding identifier
     *
     * @return                      Holding identifier for asset or currency
     */
    public long getHoldingId() {
        return holdingId;
    }

    /**
     * Return the account property name
     *
     * @return                      Account property
     */
    public String getProperty() {
        return property;
    }

    /**
     * Return the fund amount
     *
     * @return                      Fund amount
     */
    public long getAmount() {
        return amount;
    }

    /**
     * Return the fund threshold
     *
     * @return                      Fund threshold
     */
    public long getThreshold() {
        return threshold;
    }

    /**
     * Return the fund interval
     *
     * @return                      Fund interval
     */
    public int getInterval() {
        return interval;
    }

    /**
     * Return the fund account identifier
     *
     * @return                      Account identifier
     */
    public long getAccountId() {
        return accountId;
    }

    /**
     * Return the fund account name
     *
     * @return                      Account name
     */
    public String getAccountName() {
        return accountName;
    }

    /**
     * Start account monitor
     *
     * One or more funding parameters can be overriden in the account property value
     * string: amount=n,threshold=n,interval=n.
     *
     * @param   monitorType         Monitor type
     * @param   holdingId           Asset or currency identifier, ignored for NXT monitor
     * @param   property            Account property name
     * @param   amount              Fund amount
     * @param   threshold           Fund threshold
     * @param   interval            Fund interval
     * @param   accountId           Fund account identifier
     * @param   secretPhrase        Fund account secret phrase
     * @return                      TRUE if the monitor was started
     */
    public static boolean startMonitor(MonitorType monitorType, long holdingId, String property,
                                    long amount, long threshold, int interval,
                                    long accountId, String secretPhrase) {
        //
        // Initialize account monitor processing if it hasn't been done yet.  We do this now
        // instead of during NRS initialization so we don't start the monitor thread if it
        // won't be used.
        //
        init();
        //
        // Create the account monitor
        //
        AccountMonitor monitor = new AccountMonitor(monitorType, holdingId, property,
                amount, threshold, interval, accountId, secretPhrase);
        //
        // Locate monitored accounts based on the account property and the setter identifier
        //
        List<MonitoredAccount> accountList = new ArrayList<>();
        try (DbIterator<Account.AccountProperty> it = Account.getProperties(0, accountId, property, 0, Integer.MAX_VALUE)) {
            while (it.hasNext()) {
                Account.AccountProperty accountProperty = it.next();
                MonitoredAccount account = createMonitoredAccount(accountProperty.getRecipientId(), monitor, accountProperty.getValue());
                accountList.add(account);
            }
        }
        //
        // Activate the account monitor and check each monitored account to see if we need to submit
        // an initial fund transaction
        //
        synchronized(monitors) {
            if (monitors.size() > MAX_MONITORS) {
                throw new RuntimeException("Maximum of " + MAX_MONITORS + " account monitors already started");
            }
            if (monitors.contains(monitor)) {
                Logger.logDebugMessage(String.format("%s monitor already started for account %s, property '%s', holding %s",
                        monitorType.name(), monitor.accountName, property, Long.toUnsignedString(holdingId)));
                return false;
            }
            accountList.forEach(account -> {
                List<MonitoredAccount> activeList = accounts.get(account.accountId);
                if (activeList == null) {
                    activeList = new ArrayList<>();
                    accounts.put(account.accountId, activeList);
                }
                activeList.add(account);
                pendingEvents.add(account);
                Logger.logDebugMessage(String.format("Created %s monitor for target account %s, property '%s', holding %s, "
                        + "amount %d, threshold %d, interval %d",
                        monitorType.name(), account.accountName, monitor.property, Long.toUnsignedString(monitor.holdingId),
                        account.amount, account.threshold, account.interval));
            });
            monitors.add(monitor);
            Logger.logInfoMessage(String.format("%s monitor started for funding account %s, property '%s', holding %s",
                    monitorType.name(), monitor.accountName, monitor.property, Long.toUnsignedString(monitor.holdingId)));
        }
        return true;
    }

    /**
     * Create a monitored account
     *
     * The amount, threshold and interval values specified when the monitor was started can be overridden
     * by specifying one or more comma-separated values in the property value string in the format
     * 'amount=n,interval=n,threshold=n'
     *
     * @param   accountId           Account identifier
     * @param   monitor             Account monitor
     * @param   propertyValue       Account property value
     * @return                      Monitored account
     */
    private static MonitoredAccount createMonitoredAccount(long accountId, AccountMonitor monitor, String propertyValue) {
        long monitorAmount = monitor.amount;
        long monitorThreshold = monitor.threshold;
        int monitorInterval = monitor.interval;
        if (propertyValue != null && !propertyValue.isEmpty()) {
            String[] values = propertyValue.split(",");
            if (values.length == 0) {
                throw new IllegalArgumentException(
                                String.format("Account %s, property '%s', value '%s' is not valid",
                                Crypto.rsEncode(accountId), monitor.property, propertyValue));
            }
            for (String value : values) {
                int pos = value.indexOf('=');
                if (pos < 1) {
                    throw new IllegalArgumentException(
                                    String.format("Account %s, property '%s', value '%s' is not valid",
                                    Crypto.rsEncode(accountId), monitor.property, value));
                }
                String name = value.substring(0, pos).trim().toLowerCase();
                String param = value.substring(pos+1).trim();
                switch (name) {
                    case "amount":
                        monitorAmount = Long.valueOf(param);
                        if (monitorAmount < MIN_FUND_AMOUNT) {
                            throw new IllegalArgumentException("Minimum fund amount is " + MIN_FUND_AMOUNT);
                        }
                        break;
                    case "threshold":
                        monitorThreshold = Long.valueOf(param);
                        if (monitorThreshold < MIN_FUND_THRESHOLD) {
                            throw new IllegalArgumentException("Minimum fund threshold is " + MIN_FUND_THRESHOLD);
                        }
                        break;
                    case "interval":
                        monitorInterval = Integer.valueOf(param);
                        if (monitorInterval < MIN_FUND_INTERVAL) {
                            throw new IllegalArgumentException("Minimum fund interval is " + MIN_FUND_INTERVAL);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException(
                                    String.format("Account %s, property '%s', value '%s' is not valid",
                                    Crypto.rsEncode(accountId), monitor.property, value));
                }
            }
        }
        return new MonitoredAccount(accountId, monitor, monitorAmount, monitorThreshold, monitorInterval);
    }

    /**
     * Stop all account monitors
     *
     * Pending fund transactions will still be processed
     *
     * @return                      Number of monitors stopped
     */
    public static int stopAllMonitors() {
        int stopCount;
        synchronized(monitors) {
            stopCount = monitors.size();
            monitors.clear();
            accounts.clear();
        }
        Logger.logInfoMessage("All account monitors stopped");
        return stopCount;
    }

    /**
     * Stop account monitor
     *
     * Pending fund transactions will still be processed
     *
     * @param   monitorType         Monitor type
     * @param   holdingId           Asset or currency identifier, ignored for NXT monotir
     * @param   property            Account property
     * @param   accountId           Fund account identifier
     * @return                      TRUE if the monitor was stopped
     */
    public static boolean stopMonitor(MonitorType monitorType, long holdingId, String property, long accountId) {
        AccountMonitor monitor = null;
        boolean wasStopped = false;
        synchronized(monitors) {
            //
            // Deactivate the monitor
            //
            Iterator<AccountMonitor> monitorIt = monitors.iterator();
            while (monitorIt.hasNext()) {
                monitor = monitorIt.next();
                if (monitor.monitorType == monitorType && monitor.property.equals(property) &&
                        (monitorType == MonitorType.NXT || monitor.holdingId == holdingId) &&
                        monitor.accountId == accountId) {
                    monitorIt.remove();
                    wasStopped = true;
                    break;
                }
            }
            //
            // Remove monitored accounts (pending fund transactions will still be processed)
            //
            if (wasStopped) {
                Iterator<List<MonitoredAccount>> accountListIt = accounts.values().iterator();
                while (accountListIt.hasNext()) {
                    List<MonitoredAccount> accountList = accountListIt.next();
                    Iterator<MonitoredAccount> accountIt = accountList.iterator();
                    while (accountIt.hasNext()) {
                        MonitoredAccount account = accountIt.next();
                        if (account.monitor == monitor) {
                            accountIt.remove();
                            if (accountList.isEmpty()) {
                                accountListIt.remove();
                            }
                            break;
                        }
                    }
                }
                Logger.logInfoMessage(String.format("%s monitor stopped for fund account %s, property '%s', holding %d",
                    monitorType.name(), monitor.accountName, monitor.property, monitor.holdingId));
            }
        }
        return wasStopped;
    }

    /**
     * Get account monitor
     *
     * @param   monitorType         Monitor type
     * @param   holdingId           Asset or currency identifier, ignored for NXT monitor
     * @param   property            Account property
     * @param   accountId           Account identifier
     * @return                      Account monitor or null
     */
    public static AccountMonitor getMonitor(MonitorType monitorType, long holdingId, String property, long accountId) {
        AccountMonitor result = null;
        synchronized(monitors) {
            for (AccountMonitor monitor : monitors) {
                if (monitor.monitorType == monitorType && monitor.holdingId == holdingId &&
                        monitor.property.equals(property) && monitor.accountId == accountId) {
                    result = monitor;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Get all account monitors
     *
     * @return                      Account monitor list
     */
    public static List<AccountMonitor> getAllMonitors() {
        List<AccountMonitor> allMonitors = new ArrayList<>();
        synchronized(monitors) {
            allMonitors.addAll(monitors);
        }
        return allMonitors;
    }

    /**
     * Initialize monitor processing
     */
    private static synchronized void init() {
        if (stopped) {
            throw new RuntimeException("Account monitor processing has been stopped");
        }
        if (started) {
            return;
        }
        try {
            //
            // Create the monitor processing thread
            //
            Thread processingThread = new ProcessEvents();
            processingThread.start();
            //
            // Register our event listeners
            //
            Account.addListener(new AccountEventHandler(), Account.Event.BALANCE);
            Account.addAssetListener(new AssetEventHandler(), Account.Event.ASSET_BALANCE);
            Account.addCurrencyListener(new CurrencyEventHandler(), Account.Event.CURRENCY_BALANCE);
            Account.addPropertyListener(new SetPropertyEventHandler(), Account.Event.SET_PROPERTY);
            Account.addPropertyListener(new DeletePropertyEventHandler(), Account.Event.DELETE_PROPERTY);
            Nxt.getBlockchainProcessor().addListener(new BlockEventHandler(), BlockchainProcessor.Event.BLOCK_PUSHED);
            //
            // All done
            //
            started = true;
            Logger.logDebugMessage("Account monitor initialization completed");
        } catch (RuntimeException exc) {
            stopped = true;
            Logger.logErrorMessage("Account monitor initialization failed", exc);
            throw exc;
        }
    }

    /**
     * Stop monitor processing
     */
    public static void shutdown() {
        if (started && !stopped) {
            stopped = true;
            processSemaphore.release();
        }
    }

    /**
     * Return the hash code
     *
     * @return                      Hash code
     */
    @Override
    public int hashCode() {
        return monitorType.hashCode() + (int)holdingId + property.hashCode() + (int)accountId;
    }

    /**
     * Check if two account monitors are equal
     *
     * @param   obj                 Comparison object
     * @return                      TRUE if the objects are equal
     */
    @Override
    public boolean equals(Object obj) {
        boolean isEqual = false;
        if (obj != null && (obj instanceof AccountMonitor)) {
            AccountMonitor monitor = (AccountMonitor)obj;
            if (monitorType == monitor.monitorType && holdingId == monitor.holdingId &&
                    property.equals(monitor.property) && accountId == monitor.accountId) {
                isEqual = true;
            }
        }
        return isEqual;
    }

    /**
     * Process pending account event
     */
    private static class ProcessEvents extends Thread {

        /**
         * Process pending updates
         */
        @Override
        public void run() {
            Logger.logDebugMessage("Account monitor thread started");
            List<MonitoredAccount> suspendedEvents = new ArrayList<>();
            try {
                while (true) {
                    //
                    // Wait for a block to be pushed and then process pending account events
                    //
                    processSemaphore.acquire();
                    if (stopped) {
                        Logger.logDebugMessage("Account monitor thread stopped");
                        break;
                    }
                    MonitoredAccount monitoredAccount;
                    while ((monitoredAccount = pendingEvents.poll()) != null) {
                        try {
                            Account targetAccount = Account.getAccount(monitoredAccount.accountId);
                            Account fundingAccount = Account.getAccount(monitoredAccount.monitor.accountId);
                            if (Nxt.getBlockchain().getHeight() - monitoredAccount.height < monitoredAccount.interval) {
                                if (!suspendedEvents.contains(monitoredAccount)) {
                                    suspendedEvents.add(monitoredAccount);
                                }
                            } else if (targetAccount == null) {
                                Logger.logErrorMessage(String.format("Monitored account %s no longer exists",
                                        monitoredAccount.accountName));
                            } else if (fundingAccount == null) {
                                Logger.logErrorMessage(String.format("Funding account %s no longer exists",
                                        monitoredAccount.monitor.accountName));
                            } else {
                                switch (monitoredAccount.monitor.monitorType) {
                                    case NXT:
                                        processNxtEvent(monitoredAccount, targetAccount, fundingAccount);
                                        break;
                                    case ASSET:
                                        processAssetEvent(monitoredAccount, targetAccount, fundingAccount);
                                        break;
                                    case CURRENCY:
                                        processCurrencyEvent(monitoredAccount, targetAccount, fundingAccount);
                                        break;
                                }
                            }
                        } catch (Exception exc) {
                            Logger.logErrorMessage(String.format("Unable to process %s event for account %s, property '%s', holding %s",
                                    monitoredAccount.monitor.monitorType.name(), monitoredAccount.accountName,
                                    monitoredAccount.monitor.property, Long.toUnsignedString(monitoredAccount.monitor.holdingId)), exc);
                        }
                    }
                    if (!suspendedEvents.isEmpty()) {
                        pendingEvents.addAll(suspendedEvents);
                        suspendedEvents.clear();
                    }
                }
            } catch (InterruptedException exc) {
                Logger.logDebugMessage("Account monitor thread interrupted");
            } catch (Throwable exc) {
                Logger.logErrorMessage("Account monitor thread terminated", exc);
            }
        }
    }

    /**
     * Process a NXT event
     *
     * @param   monitoredAccount            Monitored account
     * @param   targetAccount               Target account
     * @param   fundingAccount              Funding account
     * @throws  NxtException                Unable to create transaction
     */
    private static void processNxtEvent(MonitoredAccount monitoredAccount, Account targetAccount, Account fundingAccount)
                                            throws NxtException {
        AccountMonitor monitor = monitoredAccount.monitor;
        if (targetAccount.getBalanceNQT() < monitoredAccount.threshold) {
            Transaction.Builder builder = Nxt.newTransactionBuilder(Crypto.getPublicKey(monitor.secretPhrase),
                    monitoredAccount.amount, 0, (short)1440, Attachment.ORDINARY_PAYMENT);
            builder.recipientId(monitoredAccount.accountId)
                   .timestamp(Nxt.getBlockchain().getLastBlockTimestamp());
            Transaction transaction = builder.build(monitor.secretPhrase);
            if (Math.addExact(monitoredAccount.amount, transaction.getFeeNQT()) > fundingAccount.getUnconfirmedBalanceNQT()) {
                Logger.logWarningMessage(String.format("Funding account %s has insufficient funds; funding transaction discarded",
                        monitor.accountName));
            } else {
                Nxt.getTransactionProcessor().broadcast(transaction);
                monitoredAccount.height = Nxt.getBlockchain().getHeight();
                Logger.logDebugMessage(String.format("NXT funding transaction %s for %s NXT submitted from %s to %s",
                        Long.toUnsignedString(transaction.getId()), Long.toUnsignedString(monitoredAccount.amount),
                        monitor.accountName, monitoredAccount.accountName));
            }
        }
    }

    /**
     * Process an ASSET event
     *
     * @param   monitoredAccount            Monitored account
     * @param   targetAccount               Target account
     * @param   fundingAccount              Funding account
     * @throws  NxtException                Unable to create transaction
     */
    private static void processAssetEvent(MonitoredAccount monitoredAccount, Account targetAccount, Account fundingAccount)
                                            throws NxtException {
        AccountMonitor monitor = monitoredAccount.monitor;
        Account.AccountAsset targetAsset = Account.getAccountAsset(targetAccount.getId(), monitor.holdingId);
        Account.AccountAsset fundingAsset = Account.getAccountAsset(fundingAccount.getId(), monitor.holdingId);
        if (fundingAsset == null || fundingAsset.getQuantityQNT() < monitoredAccount.amount) {
            Logger.logWarningMessage(
                    String.format("Funding account %s has insufficient quantity for asset %s; funding transaction discarded",
                            monitor.accountName, Long.toUnsignedString(monitor.holdingId)));
        } else if (targetAsset == null || targetAsset.getQuantityQNT() < monitoredAccount.threshold) {
            Attachment attachment = new Attachment.ColoredCoinsAssetTransfer(monitor.holdingId, monitoredAccount.amount);
            Transaction.Builder builder = Nxt.newTransactionBuilder(Crypto.getPublicKey(monitor.secretPhrase),
                    0, 0, (short)1440, attachment);
            builder.recipientId(monitoredAccount.accountId)
                   .timestamp(Nxt.getBlockchain().getLastBlockTimestamp());
            Transaction transaction = builder.build(monitor.secretPhrase);
            if (transaction.getFeeNQT() > fundingAccount.getUnconfirmedBalanceNQT()) {
                Logger.logWarningMessage(String.format("Funding account %s has insufficient funds; funding transaction discarded",
                        monitor.accountName));
            } else {
                Nxt.getTransactionProcessor().broadcast(transaction);
                monitoredAccount.height = Nxt.getBlockchain().getHeight();
                Logger.logDebugMessage(String.format("ASSET funding transaction %s submitted for %s units from %s to %s",
                        Long.toUnsignedString(transaction.getId()), Long.toUnsignedString(monitoredAccount.amount),
                        monitor.accountName, monitoredAccount.accountName));
            }
        }
    }

    /**
     * Process a CURRENCY event
     *
     * @param   monitoredAccount            Monitored account
     * @param   targetAccount               Target account
     * @param   fundingAccount              Funding account
     * @throws  NxtException                Unable to create transaction
     */
    private static void processCurrencyEvent(MonitoredAccount monitoredAccount, Account targetAccount, Account fundingAccount)
                                            throws NxtException {
        AccountMonitor monitor = monitoredAccount.monitor;
        Account.AccountCurrency targetCurrency = Account.getAccountCurrency(targetAccount.getId(), monitor.holdingId);
        Account.AccountCurrency fundingCurrency = Account.getAccountCurrency(fundingAccount.getId(), monitor.holdingId);
        if (fundingCurrency == null || fundingCurrency.getUnits() < monitoredAccount.amount) {
            Logger.logWarningMessage(
                    String.format("Funding account %s has insufficient quantity for currency %s; funding transaction discarded",
                            monitor.accountName, Long.toUnsignedString(monitor.holdingId)));
        } else if (targetCurrency == null || targetCurrency.getUnits() < monitoredAccount.threshold) {
            Attachment attachment = new Attachment.MonetarySystemCurrencyTransfer(monitor.holdingId, monitoredAccount.amount);
            Transaction.Builder builder = Nxt.newTransactionBuilder(Crypto.getPublicKey(monitor.secretPhrase),
                    0, 0, (short)1440, attachment);
            builder.recipientId(monitoredAccount.accountId)
                   .timestamp(Nxt.getBlockchain().getLastBlockTimestamp());
            Transaction transaction = builder.build(monitor.secretPhrase);
            if (transaction.getFeeNQT() > fundingAccount.getUnconfirmedBalanceNQT()) {
                Logger.logWarningMessage(String.format("Funding account %s has insufficient funds; funding transaction discarded",
                        monitor.accountName));
            } else {
                Nxt.getTransactionProcessor().broadcast(transaction);
                monitoredAccount.height = Nxt.getBlockchain().getHeight();
                Logger.logDebugMessage(String.format("CURRENCY funding transaction %s submitted for %s units from %s to %s",
                        Long.toUnsignedString(transaction.getId()), Long.toUnsignedString(monitoredAccount.amount),
                        monitor.accountName, monitoredAccount.accountName));
            }
        }
    }

    /**
     * Monitored account
     */
    private static final class MonitoredAccount {

        /** Account identifier */
        private final long accountId;

        /** Account name */
        private final String accountName;

        /** Associated account monitor */
        private final AccountMonitor monitor;

        /** Fund amount */
        private long amount;

        /** Fund threshold */
        private long threshold;

        /** Fund interval */
        private  int interval;

        /** Last fund height */
        private int height;

        /**
         * Create a new monitored account
         *
         * @param   accountId           Account identifier
         * @param   monitor             Account monitor
         * @param   amount              Fund amount
         * @param   threshold           Fund threshold
         * @param   interval            Fund interval
         */
        public MonitoredAccount(long accountId, AccountMonitor monitor, long amount, long threshold, int interval) {
            this.accountId = accountId;
            this.accountName = Crypto.rsEncode(accountId);
            this.monitor = monitor;
            this.amount = amount;
            this.threshold = threshold;
            this.interval = interval;
        }
    }

    /**
     * Account event handler (BALANCE event)
     */
    private static final class AccountEventHandler implements Listener<Account> {

        /**
         * Account event notification
         *
         * @param   account                 Account
         */
        @Override
        public void notify(Account account) {
            if (stopped) {
                return;
            }
            long balance = account.getBalanceNQT();
            //
            // Check the NXT balance for monitored accounts
            //
            synchronized(monitors) {
                List<MonitoredAccount> accountList = accounts.get(account.getId());
                if (accountList != null) {
                    accountList.forEach((maccount) -> {
                       if (maccount.monitor.monitorType == MonitorType.NXT && balance < maccount.threshold &&
                               !pendingEvents.contains(maccount)) {
                           pendingEvents.add(maccount);
                       }
                    });
                }
            }
        }
    }

    /**
     * Asset event handler (ASSET_BALANCE event)
     */
    private static final class AssetEventHandler implements Listener<Account.AccountAsset> {

        /**
         * Asset event notification
         *
         * @param   asset                   Account asset
         */
        @Override
        public void notify(Account.AccountAsset asset) {
            if (stopped) {
                return;
            }
            long balance = asset.getQuantityQNT();
            long assetId = asset.getAssetId();
            //
            // Check the asset balance for monitored accounts
            //
            synchronized(monitors) {
                List<MonitoredAccount> accountList = accounts.get(asset.getAccountId());
                if (accountList != null) {
                    accountList.forEach((maccount) -> {
                        if (maccount.monitor.monitorType == MonitorType.ASSET &&
                                maccount.monitor.holdingId == assetId &&
                                balance < maccount.threshold &&
                                !pendingEvents.contains(maccount)) {
                            pendingEvents.add(maccount);
                        }
                    });
                }
            }
        }
    }

    /**
     * Currency event handler (CURRENCY_BALANCE event)
     */
    private static final class CurrencyEventHandler implements Listener<Account.AccountCurrency> {

        /**
         * Currency event notification
         *
         * @param   currency                Account currency
         */
        @Override
        public void notify(Account.AccountCurrency currency) {
            if (stopped) {
                return;
            }
            long balance = currency.getUnits();
            long currencyId = currency.getCurrencyId();
            //
            // Check the currency balance for monitored accounts
            //
            synchronized(monitors) {
                List<MonitoredAccount> accountList = accounts.get(currency.getAccountId());
                if (accountList != null) {
                    accountList.forEach((maccount) -> {
                        if (maccount.monitor.monitorType == MonitorType.CURRENCY &&
                                maccount.monitor.holdingId == currencyId &&
                                balance < maccount.threshold &&
                                !pendingEvents.contains(maccount)) {
                            pendingEvents.add(maccount);
                        }
                    });
                }
            }
        }
    }

    /**
     * Property event handler (SET_PROPERTY event)
     */
    private static final class SetPropertyEventHandler implements Listener<Account.AccountProperty> {

        /**
         * Property event notification
         *
         * @param   property                Account property
         */
        @Override
        public void notify(Account.AccountProperty property) {
            if (stopped) {
                return;
            }
            long accountId = property.getRecipientId();
            try {
                boolean addMonitoredAccount = true;
                synchronized(monitors) {
                    //
                    // Check if updating an existing monitored account.  In this case, we don't need to create
                    // a new monitored account and just need to update any monitor overrides.
                    //
                    List<MonitoredAccount> accountList = accounts.get(accountId);
                    if (accountList != null) {
                        for (MonitoredAccount account : accountList) {
                            if (account.monitor.property.equals(property.getProperty())) {
                                addMonitoredAccount = false;
                                MonitoredAccount newAccount = createMonitoredAccount(accountId, account.monitor, property.getValue());
                                account.amount = newAccount.amount;
                                account.threshold = newAccount.threshold;
                                account.interval = newAccount.interval;
                                pendingEvents.add(account);
                                Logger.logDebugMessage(
                                        String.format("Updated %s monitor for account %s, property '%s', holding %s, "
                                                + "amount %d, threshold %d, interval %d",
                                                account.monitor.monitorType.name(), account.accountName,
                                                property.getProperty(), Long.toUnsignedString(account.monitor.holdingId),
                                                account.amount, account.threshold, account.interval));
                            }
                        }
                    }
                    //
                    // Create a new monitored account if there is an active monitor for this account property
                    //
                    if (addMonitoredAccount) {
                        for (AccountMonitor monitor : monitors) {
                            if (monitor.property.equals(property.getProperty())) {
                                MonitoredAccount account = createMonitoredAccount(accountId, monitor, property.getValue());
                                accountList = accounts.get(accountId);
                                if (accountList == null) {
                                    accountList = new ArrayList<>();
                                    accounts.put(accountId, accountList);
                                }
                                accountList.add(account);
                                pendingEvents.add(account);
                                Logger.logDebugMessage(
                                        String.format("Created %s monitor for account %s, property '%s', holding %s, "
                                                + "amount %d, threshold %d, interval %d",
                                                monitor.monitorType.name(), account.accountName,
                                                property.getProperty(), Long.toUnsignedString(monitor.holdingId),
                                                account.amount, account.threshold, account.interval));
                            }
                        }
                    }
                }
            } catch (Exception exc) {
                Logger.logErrorMessage("Unable to process SET_PROPERTY event for account " + Crypto.rsEncode(accountId), exc);
            }
        }
    }

    /**
     * Property event handler (DELETE_PROPERTY event)
     */
    private static final class DeletePropertyEventHandler implements Listener<Account.AccountProperty> {

        /**
         * Property event notification
         *
         * @param   property                Account property
         */
        @Override
        public void notify(Account.AccountProperty property) {
            if (stopped) {
                return;
            }
            long accountId = property.getRecipientId();
            synchronized(monitors) {
                List<MonitoredAccount> accountList = accounts.get(accountId);
                if (accountList != null) {
                    Iterator<MonitoredAccount> it = accountList.iterator();
                    while (it.hasNext()) {
                        MonitoredAccount account = it.next();
                        if (account.monitor.property.equals(property.getProperty())) {
                            it.remove();
                            Logger.logDebugMessage(
                                    String.format("Deleted %s monitor for account %s, property '%s', holding %s",
                                            account.monitor.monitorType.name(), account.accountName,
                                            property.getProperty(), Long.toUnsignedString(account.monitor.holdingId)));
                        }
                    }
                    if (accountList.isEmpty()) {
                        accounts.remove(accountId);
                    }
                }
            }
        }
    }

    /**
     * Block event handler (BLOCK_PUSHED event)
     *
     * We will process pending funding events when a block is pushed to the blockchain.  This ensures that all
     * block transactions have been processed before we process funding events.
     */
    private static final class BlockEventHandler implements Listener<Block> {

        /**
         * Block event notification
         */
        @Override
        public void notify(Block block) {
            if (!stopped && !pendingEvents.isEmpty()) {
                processSemaphore.release();
            }
        }
    }
}
