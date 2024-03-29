package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.LockContext;
import edu.berkeley.cs186.database.concurrency.LockType;
import edu.berkeley.cs186.database.concurrency.LockUtil;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Lock context of the entire database.
    private LockContext dbContext;
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given transaction number.
    private Function<Long, Transaction> newTransaction;
    // Function to update the transaction counter.
    protected Consumer<Long> updateTransactionCounter;
    // Function to get the transaction counter.
    protected Supplier<Long> getTransactionCounter;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();

    // List of lock requests made during recovery. This is only populated when locking is disabled.
    List<String> lockRequests;

    public ARIESRecoveryManager(LockContext dbContext, Function<Long, Transaction> newTransaction,
                                Consumer<Long> updateTransactionCounter, Supplier<Long> getTransactionCounter) {
        this(dbContext, newTransaction, updateTransactionCounter, getTransactionCounter, false);
    }

    ARIESRecoveryManager(LockContext dbContext, Function<Long, Transaction> newTransaction,
                         Consumer<Long> updateTransactionCounter, Supplier<Long> getTransactionCounter,
                         boolean disableLocking) {
        this.dbContext = dbContext;
        this.newTransaction = newTransaction;
        this.updateTransactionCounter = updateTransactionCounter;
        this.getTransactionCounter = getTransactionCounter;
        this.lockRequests = disableLocking ? new ArrayList<>() : null;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     *
     * The master record should be added to the log, and a checkpoint should be taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor because of the cyclic dependency
     * between the buffer manager and recovery manager (the buffer manager must interface with the
     * recovery manager to block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManagerImpl(bufferManager);
    }

    // Forward Processing ////////////////////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be emitted, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement
        // emit log record
        LogRecord record = new CommitTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN);
        logManager.appendToLog(record);
        // flush log
        logManager.flushToLSN(record.getLSN());
        // update transaction table
        TransactionTableEntry transaction = transactionTable.get(transNum);
        transaction.lastLSN = record.getLSN();
        // update transaction status
        transaction.transaction.setStatus(Transaction.Status.COMMITTING);
        return record.getLSN();
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be emitted, and the transaction table and transaction
     * status should be updated. No CLRs should be emitted.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement
        // emit abort record
        LogRecord record = new AbortTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN);
        logManager.appendToLog(record);
        // update transaction table
        TransactionTableEntry transaction = transactionTable.get(transNum);
        transaction.lastLSN = record.getLSN();
        // update transaction status
        transaction.transaction.setStatus(Transaction.Status.ABORTING);
        return record.getLSN();
    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting.
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be emitted,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        // undo changes, that needs to be undone
        if (transactionTable.get(transNum).transaction.getStatus() == Transaction.Status.ABORTING) {
            undoUpToLSN(transNum, -1L);
        }
        // emit end record
        LogRecord record = new EndTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN);
        logManager.appendToLog(record);
        // update transaction status
        TransactionTableEntry transaction = transactionTable.get(transNum);
        transaction.lastLSN = record.getLSN();
        transaction.transaction.setStatus(Transaction.Status.COMPLETE);
        // remove transaction
        transactionTable.remove(transNum);
        return record.getLSN();
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be emitted; if the number of bytes written is
     * too large (larger than BufferManager.EFFECTIVE_PAGE_SIZE / 2), then two records
     * should be written instead: an undo-only record followed by a redo-only record.
     *
     * Both the transaction table and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        // TODO(proj5): implement
        TransactionTableEntry transaction = transactionTable.get(transNum);
        // split log record into two, as it cannot store bth before and after
        // first, and undo-only update record, next, a redo-only record.
        if (before.length + after.length > BufferManager.EFFECTIVE_PAGE_SIZE / 2) {
            UpdatePageLogRecord undoRec = new UpdatePageLogRecord(transNum, pageNum, transaction.lastLSN, pageOffset, before, null);
            UpdatePageLogRecord redoRec = new UpdatePageLogRecord(transNum, pageNum, transaction.lastLSN, pageOffset, null, after);
            logManager.appendToLog(undoRec);
            logManager.appendToLog(redoRec);
            transaction.lastLSN = redoRec.getLSN();
            dirtyPageTable.putIfAbsent(pageNum, undoRec.LSN);
            transaction.touchedPages.add(pageNum);
            return redoRec.getLSN();
        }
        // emit single update record with redo and undo information
        else {
            UpdatePageLogRecord undoRedoRec = new UpdatePageLogRecord(transNum, pageNum, transaction.lastLSN, pageOffset, before, after);
            logManager.appendToLog(undoRedoRec);
            transaction.lastLSN = undoRedoRec.getLSN();
            dirtyPageTable.putIfAbsent(pageNum, undoRedoRec.LSN);
            transaction.touchedPages.add(pageNum);
            return undoRedoRec.getLSN();
        }
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) {
            return -1L;
        }

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) {
            return -1L;
        }

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) {
            return -1L;
        }

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN, touchedPages
        transactionEntry.lastLSN = LSN;
        transactionEntry.touchedPages.add(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) {
            return -1L;
        }

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN, touchedPages
        transactionEntry.lastLSN = LSN;
        transactionEntry.touchedPages.add(pageNum);
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long LSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        undoUpToLSN(transNum, LSN);
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible,
     * using recLSNs from the DPT, then status/lastLSNs from the transactions table,
     * and then finally, touchedPages from the transactions table, and written
     * when full (or when done).
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord(getTransactionCounter.get());
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> dpt = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> txnTable = new HashMap<>();
        Map<Long, List<Long>> touchedPages = new HashMap<>();
        int numTouchedPages = 0;

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table
        // iterate through dirtyPageTable, copy entries
        // if copying current would cause the end checkpoint to be too large, append end checkpoint to log
        for (Map.Entry<Long, Long> dptXact : dirtyPageTable.entrySet()) {
            boolean fitsOnOnePageAfterAdd;
            fitsOnOnePageAfterAdd = EndCheckpointLogRecord.fitsInOneRecord(dpt.size() + 1,
                    txnTable.size(), touchedPages.size(), numTouchedPages);
            if (!fitsOnOnePageAfterAdd) {
                LogRecord endRecord = new EndCheckpointLogRecord(dpt, txnTable, touchedPages);
                logManager.appendToLog(endRecord);
                dpt.clear();
                txnTable.clear();
                touchedPages.clear();
                numTouchedPages = 0;
            }
            dpt.put(dptXact.getKey(), dptXact.getValue());
        }

        // iterate thought transactionTable, copy the status/lastLSN, outputting end checkpoint records as needed
        for (Map.Entry<Long, TransactionTableEntry> txn : transactionTable.entrySet()) {
            boolean fitsOneOnePageAfterAdd = EndCheckpointLogRecord.fitsInOneRecord(dpt.size(),
                    txnTable.size() + 1, touchedPages.size(), numTouchedPages);
            if (!fitsOneOnePageAfterAdd) {
                LogRecord endRecord = new EndCheckpointLogRecord(dpt, txnTable, touchedPages);
                logManager.appendToLog(endRecord);
                dpt.clear();
                txnTable.clear();
                touchedPages.clear();
                numTouchedPages = 0;
            }
            Long txnNum = txn.getKey();
            Transaction.Status txnStatus = txn.getValue().transaction.getStatus();
            Long lastLSN = txn.getValue().lastLSN;
            txnTable.put(txnNum, new Pair<>(txnStatus, lastLSN));
        }

        // iterate through transactionTable, and copy touched pages, outputting end checkpoint records as needed.
        for (Map.Entry<Long, TransactionTableEntry> txn : transactionTable.entrySet()) {
            Long transNum = txn.getKey();
            for (Long touchedPageNum : txn.getValue().touchedPages) {
                boolean fitsOnOnePageAfterAdd;
                if (!touchedPages.containsKey(transNum)) {
                    fitsOnOnePageAfterAdd= EndCheckpointLogRecord.fitsInOneRecord(dpt.size(), txnTable.size(),
                            touchedPages.size() + 1, numTouchedPages + 1);
                } else {
                    fitsOnOnePageAfterAdd= EndCheckpointLogRecord.fitsInOneRecord(dpt.size(), txnTable.size(),
                            touchedPages.size(), numTouchedPages + 1);
                }

                if (!fitsOnOnePageAfterAdd) {
                    LogRecord endRecord = new EndCheckpointLogRecord(dpt, txnTable, touchedPages);
                    logManager.appendToLog(endRecord);

                    dpt.clear();
                    txnTable.clear();
                    touchedPages.clear();
                    numTouchedPages = 0;
                }

                touchedPages.computeIfAbsent(transNum, t -> new ArrayList<>());
                touchedPages.get(transNum).add(touchedPageNum);
                ++numTouchedPages;
            }
        }

        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(dpt, txnTable, touchedPages);
        logManager.appendToLog(endRecord);

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    // TODO(proj5): add any helper methods needed

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery //////////////////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery. Recovery is
     * complete when the Runnable returned is run to termination. New transactions may be
     * started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the dirty page
     * table of non-dirty pages (pages that aren't dirty in the buffer manager) between
     * redo and undo, and perform a checkpoint after undo.
     *
     * This method should return right before undo is performed.
     *
     * @return Runnable to run to finish restart recovery
     */
    @Override
    public Runnable restart() {
        // TODO(proj5): implement
        // restart analysis
        restartAnalysis();
        // redo
        restartRedo();
        // between redo and undo, remove any not-dirty page (was not flushed to disk)
        for (Map.Entry<Long, Long> page : dirtyPageTable.entrySet()) {
            if (bufferManager.fetchPage(getPageLockContext(page.getKey()).parentContext(),
                    page.getKey(), false).getPageLSN() >= logManager.getFlushedLSN()) {
                dirtyPageTable.remove(page.getKey());
            }
        }
        // Allow new transactions to start running, return Runnable to do undo phase and checkpoiont
        // Database object runs the Runnable object in the background after restart returns
        return () -> {
            // undo
            restartUndo();
            // new checkpoint, to avoid aborting trans again, should we crash
            checkpoint();
        };
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the begin checkpoint record.
     *
     * If the log record is for a transaction operation:
     * - update the transaction table
     * - if it's page-related (as opposed to partition-related),
     *   - add to touchedPages
     *   - acquire X lock
     *   - update DPT (alloc/free/undoalloc/undofree always flushes changes to disk)
     *
     * If the log record is for a change in transaction status:
     * - clean up transaction (Transaction#cleanup) if END_TRANSACTION
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     *
     * If the log record is a begin_checkpoint record:
     * - Update the transaction counter
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Update lastLSN to be the larger of the existing entry's (if any) and the checkpoint's;
     *   add to transaction table if not already present.
     * - Add page numbers from checkpoint's touchedPages to the touchedPages sets in the
     *   transaction table if the transaction has not finished yet, and acquire X locks.
     *
     * Then, cleanup and end transactions that are in the COMMITING state, and
     * move all transactions in the RUNNING state to RECOVERY_ABORTING.
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        assert (record != null);
        // Type casting
        assert (record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;

        // TODO(proj5): implement

        // goal is to reconstruct the dirty page table and transaction table from the log
        Iterator<LogRecord> logRecordsSinceCkpt = logManager.scanFrom(LSN);
        while (logRecordsSinceCkpt.hasNext()) {
            LogRecord curLog = logRecordsSinceCkpt.next();

            // ### log records for transaction operations ###
            // i.e. CommitTransaction, AbortTransaction, EndTransaction
            if (checkWithoutFlushPageOperation(curLog) | checkWithFlushPageOperation(curLog) | checkPartOperation(curLog)) {
                Long curTransNum = curLog.getTransNum().get();
                // update transaction table
                transactionTable.putIfAbsent(curTransNum, new TransactionTableEntry(newTransaction.apply(curTransNum)));
                TransactionTableEntry tte = transactionTable.get(curTransNum);
                tte.lastLSN = curLog.LSN;
                // if log is related to a page
                if (isLogAboutPage(curLog)) {
                    Long curPageNum = curLog.getPageNum().get();
                    // add to touchedPages
                    tte.touchedPages.add(curPageNum);
                    // acquire X lock
                    acquireTransactionLock(tte.transaction, this.getPageLockContext(curPageNum), LockType.X);
                    // update DPT
                    if (checkWithoutFlushPageOperation(curLog)) {
                        dirtyPageTable.putIfAbsent(curPageNum, curLog.getLSN());
                    } else if (checkWithFlushPageOperation(curLog)) {
                        logManager.flushToLSN(curLog.getLSN());
                        dirtyPageTable.remove(curPageNum);
                    }
                }
            }

            // ### log records for change in transaction status ###
            if (checkStatus(curLog)) {
                Long curTransNum = curLog.getTransNum().get();
                // update transaction table
                transactionTable.putIfAbsent(curTransNum, new TransactionTableEntry(newTransaction.apply(curTransNum)));
                TransactionTableEntry tte = transactionTable.get(curTransNum);
                tte.lastLSN = curLog.LSN;
                if (curLog.getPageNum().isPresent()) {
                    Long curPageNum = curLog.getPageNum().get();
                    tte.touchedPages.add(curPageNum);
                    acquireTransactionLock(tte.transaction, this.getPageLockContext(curPageNum), LockType.X);
                }
                // clean up transaction (Transaction#cleanup) if END_TRANSACTION
                if (curLog.getType() == LogType.END_TRANSACTION) {
                    tte.transaction.cleanup();
                    transactionTable.remove(curTransNum);
                    tte.transaction.setStatus(Transaction.Status.COMPLETE);
                }
                // update transaction status to COMMITTING/RECOVERY_ABORTING
                else if (curLog.getType() == LogType.ABORT_TRANSACTION) {
                    tte.transaction.setStatus(Transaction.Status.ABORTING);
                }
                else if (curLog.getType() == LogType.COMMIT_TRANSACTION) {
                    tte.transaction.setStatus(Transaction.Status.COMMITTING);
                }
            }

            // If log record is a begin_checkpoint record
            if (curLog.type == LogType.BEGIN_CHECKPOINT) {
                // Update the transaction counter
                updateTransactionCounter.accept(curLog.getMaxTransactionNum().get());
            }

            // if EndTransaction, clean transaction, and remove from transaction table.
            if (curLog.type == LogType.END_CHECKPOINT) {
                // Copy all entries of checkpoint DPT (replace existing entries if any)
                for (Map.Entry<Long, Long> pageInDPT : curLog.getDirtyPageTable().entrySet()) {
                    if (curLog.getTransNum().isPresent()) {
                        dirtyPageTable.replace(pageInDPT.getKey(), pageInDPT.getValue());
                    }
                }

                for (Map.Entry<Long, Pair<Transaction.Status, Long>> pageInTransTable : curLog.getTransactionTable().entrySet()) {
                    if (curLog.getTransNum().isPresent()) {
                        Long curTransNum = curLog.getTransNum().get();
                        // add to transaction table, if not present
                        transactionTable.putIfAbsent(pageInTransTable.getKey(), new TransactionTableEntry(newTransaction.apply(curTransNum)));
                        TransactionTableEntry tte = transactionTable.get(curTransNum);
                        // Update lastLSN to be the larger of the existing entry's (if any) and the checkpoint's;
                        if (tte.lastLSN < pageInTransTable.getValue().getSecond()) {
                            transactionTable.get(pageInTransTable.getKey()).lastLSN = pageInTransTable.getValue().getSecond();
                        }
                        // If not finished yet, add page number from checkpoint's touchedPages to touchPages
                        // in transaction table and acquire X locks
                        if (pageInTransTable.getValue().getFirst() != Transaction.Status.COMPLETE) {
                            for (Long curPageNum: curLog.getTransactionTouchedPages().get(curTransNum)) {
                                tte.touchedPages.add(curPageNum);
                                this.acquireTransactionLock(tte.transaction, this.getPageLockContext(curPageNum), LockType.X);
                            }
                        } else {
                            tte.transaction.cleanup();
                        }
                        tte.transaction.setStatus(pageInTransTable.getValue().getFirst());
                    }
                }
            }
        }

        // Ending transactions
        // cleanup and end transactions that are in the COMMITING state, and
        // move all transactions in the RUNNING state to RECOVERY_ABORTING.
        for (Map.Entry<Long, TransactionTableEntry> transToComitOrRecover: transactionTable.entrySet()) {
            TransactionTableEntry tte = transToComitOrRecover.getValue();
            if (tte.transaction.getStatus() == Transaction.Status.COMMITTING) {
                tte.transaction.cleanup();
                tte.transaction.setStatus(Transaction.Status.COMPLETE);
                logManager.appendToLog(new EndTransactionLogRecord(transToComitOrRecover.getKey(), tte.lastLSN));
                transactionTable.remove(transToComitOrRecover.getKey());
            } else if (tte.transaction.getStatus() == Transaction.Status.RUNNING) {
                tte.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                LogRecord tmplr = new AbortTransactionLogRecord(transToComitOrRecover.getKey(), tte.lastLSN);
                logManager.appendToLog(tmplr);
                tte.lastLSN = tmplr.getLSN();
            }
        }

    }

    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the DPT.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - about a page (Update/Alloc/Free/Undo..Page) in the DPT with LSN >= recLSN,
     *   the page is fetched from disk and the pageLSN is checked, and the record is redone.
     * - about a partition (Alloc/Free/Undo..Part), redo it.
     */
    void restartRedo() {
        // TODO(proj5): implement
        Long lowestRecLSN = Long.MAX_VALUE;
        // begin redo at the lowest recLSN
        for (Map.Entry<Long, Long> transInDPT : dirtyPageTable.entrySet()) {
            if (transInDPT.getValue() < lowestRecLSN) {
                lowestRecLSN = transInDPT.getValue();
            }
        }
        assert (lowestRecLSN != Long.MAX_VALUE);

        // go through each log record and decide if redo
        Iterator<LogRecord> iter = logManager.scanFrom(lowestRecLSN);
        while (iter.hasNext()) {
            LogRecord curLog = iter.next();
            // is redoable and either;
            if (curLog.isRedoable()) {
                // log is related to page, AND
                if (isLogAboutPage(curLog)) {
                    Long curPageNum = curLog.getPageNum().get();
                    // page is in DPT, AND
                    if (dirtyPageTable.containsKey(curPageNum)) {
                        // LSN is not less than recLSN, AND
                        if (curLog.getLSN() >= dirtyPageTable.get(curPageNum)) {
                            Page curPage = bufferManager.fetchPage(getPageLockContext(curPageNum).parentContext(), curPageNum, false);
                            // pageLSN of page is less than LSN of record
                            if (curPage.getPageLSN() < curLog.getLSN()) {
                                curLog.redo(diskSpaceManager, bufferManager);
                            }
                        }
                    }
                }
                // log is related to partition
                else if (isLogAboutPartition(curLog)) {
                    curLog.redo(diskSpaceManager, bufferManager);
                }
            }
        }
    }

    /**
     * This method performs the redo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, emit the appropriate CLR, and update tables accordingly;
     * - replace the entry in the set should be replaced with a new one, using the undoNextLSN
     *   (or prevLSN if none) of the record; and
     * - if the new LSN is 0, end the transaction and remove it from the queue and transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        // repeatedly undo the record with highest LSN until we are done
        // to avoid a large number of random IOs
        PriorityQueue<Pair<Long, Long>> pq = new PriorityQueue<Pair<Long, Long>>(new PairFirstReverseComparator<Long, Long>());
        // add all aborting transaction to undo
        for (Map.Entry<Long, TransactionTableEntry> trans : transactionTable.entrySet()) {
            if (trans.getValue().transaction.getStatus() != Transaction.Status.RECOVERY_ABORTING) {
                continue;
            }
            pq.add(new Pair<>(trans.getValue().lastLSN,trans.getKey()));
        }

        // go through pq, starting with trans with largest LSN
        while (pq.size() > 0) {
            Pair<Long, Long> curPair = pq.poll();
            TransactionTableEntry tte = transactionTable.get(curPair.getSecond());
            LogRecord curLog = logManager.fetchLogRecord(curPair.getFirst());
            // if the record is undoable, we undo it and write the CLR out, updating the DPT as necessary
            if (curLog.isUndoable()) {
                if (isLogAboutPage(curLog)) {
                    if (dirtyPageTable.containsKey(curLog.getPageNum().get())) {
                        if (curLog.getLSN() == dirtyPageTable.get(curLog.getPageNum().get())) {
                            dirtyPageTable.remove(curLog.getPageNum().get());
                        }
                    }
                }
                LogRecord tmplr = curLog.undo(tte.lastLSN).getFirst();
                logManager.appendToLog(tmplr);
                if (curLog.undo(tte.lastLSN).getSecond()) {
                    logManager.flushToLSN(tmplr.getLSN());
                }
                tte.lastLSN = tmplr.getLSN();
                tmplr.redo(diskSpaceManager, bufferManager);
            }
            // replace the LSN in the set with the undoNextLSN of the record if it has one, and the prevLSN otherwise;
            Long newLSN = curLog.getUndoNextLSN().isPresent() ? curLog.getUndoNextLSN().get() : curLog.getPrevLSN().get();
            // end the transaction if the LSN from the previous step is 0, removing it from the set and the transaction table.
            if (newLSN == 0) {
                tte.transaction.cleanup();
                tte.transaction.setStatus(Transaction.Status.COMPLETE);
                logManager.appendToLog(new EndTransactionLogRecord(curPair.getSecond(), tte.lastLSN));
                transactionTable.remove(curPair.getSecond());
            } else {
                pq.add(new Pair<>(newLSN, curPair.getSecond()));
            }
        }
    }

    // TODO(proj5): add any helper methods needed

    // Helpers ///////////////////////////////////////////////////////////////////////////////

    /**
     * Helper to undo all logs of a Xact up to (and not including) upToLSN
     * @param transNum
     * @param upToLSN
     */
    private void undoUpToLSN(long transNum, long upToLSN) {
        LogRecord curRecord = logManager.fetchLogRecord(transactionTable.get(transNum).lastLSN);
        while(true) {
            if (curRecord.getLSN() <= upToLSN) {
                break;
            }
            if (curRecord.isUndoable()) {
                LogRecord clrToUndo = curRecord.undo(curRecord.getLSN()).getFirst();
                executeCLRforRecord(curRecord, clrToUndo);
            }
            if (curRecord.getPrevLSN().isPresent()) {
                curRecord = logManager.fetchLogRecord(curRecord.getPrevLSN().get());
            } else {
                break;
            }
        }
    }

    /**
     * Helper to update both log manager (append CLR to log and/or flush log) and execute CLR of the current log record
     * @param curRecord
     * @param clrToUndo
     */
    private void executeCLRforRecord(LogRecord curRecord, LogRecord clrToUndo) {
        logManager.appendToLog(clrToUndo);
        // If needs to be flushed
        if (curRecord.undo(curRecord.getLSN()).getSecond()) {
            logManager.flushToLSN(curRecord.getLSN());
        }
        // To execute the CLR
        clrToUndo.redo(diskSpaceManager, bufferManager);
    }

    boolean isLogAboutPartition(LogRecord lr) {
        if (lr.getPartNum().isPresent()) {
            return true;
        }
        return false;
    }

    boolean isLogAboutPage(LogRecord lr) {
        if (lr.getPageNum().isPresent()) {
            return true;
        }
        return false;
    }

    boolean checkWithoutFlushPageOperation(LogRecord lr) {
        if (lr.type.equals(LogType.UNDO_UPDATE_PAGE) | lr.type.equals(LogType.UPDATE_PAGE)) {
            return true;
        }
        return false;
    }

    boolean checkWithFlushPageOperation(LogRecord lr) {
        if (lr.type.equals(LogType.ALLOC_PAGE) | lr.type.equals(LogType.UNDO_ALLOC_PAGE) |
                lr.type.equals(LogType.FREE_PAGE) | lr.type.equals(LogType.UNDO_FREE_PAGE)) {
            return true;
        }
        return false;
    }

    boolean checkPartOperation(LogRecord lr) {
        if (lr.type.equals(LogType.ALLOC_PART) | lr.type.equals(LogType.FREE_PART) |
                lr.type.equals(LogType.UNDO_ALLOC_PART) | lr.type.equals(LogType.UNDO_FREE_PART)) {
            return true;
        }
        return false;
    }

    boolean checkStatus(LogRecord lr) {
        if (lr.type.equals(LogType.ABORT_TRANSACTION) | lr.type.equals(LogType.COMMIT_TRANSACTION) |
                lr.type.equals(LogType.END_TRANSACTION)) {
            return true;
        }
        return false;
    }

    /**
     * Returns the lock context for a given page number.
     * @param pageNum page number to get lock context for
     * @return lock context of the page
     */
    private LockContext getPageLockContext(long pageNum) {
        int partNum = DiskSpaceManager.getPartNum(pageNum);
        return this.dbContext.childContext(partNum).childContext(pageNum);
    }

    /**
     * Locks the given lock context with the specified lock type under the specified transaction,
     * acquiring locks on ancestors as needed.
     * @param transaction transaction to request lock for
     * @param lockContext lock context to lock
     * @param lockType type of lock to request
     */
    private void acquireTransactionLock(Transaction transaction, LockContext lockContext,
                                        LockType lockType) {
        acquireTransactionLock(transaction.getTransactionContext(), lockContext, lockType);
    }

    /**
     * Locks the given lock context with the specified lock type under the specified transaction,
     * acquiring locks on ancestors as needed.
     * @param transactionContext transaction context to request lock for
     * @param lockContext lock context to lock
     * @param lockType type of lock to request
     */
    private void acquireTransactionLock(TransactionContext transactionContext,
                                        LockContext lockContext, LockType lockType) {
        TransactionContext.setTransaction(transactionContext);
        try {
            if (lockRequests == null) {
                LockUtil.ensureSufficientLockHeld(lockContext, lockType);
            } else {
                lockRequests.add("request " + transactionContext.getTransNum() + " " + lockType + "(" +
                        lockContext.getResourceName() + ")");
            }
        } finally {
            TransactionContext.unsetTransaction();
        }
    }

    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A), in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
}
