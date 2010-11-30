/*
 * Copyright (c) 2004 Persistit Corporation. All Rights Reserved.
 *
 * The Java source code is the confidential and proprietary information
 * of Persistit Corporation ("Confidential Information"). You shall
 * not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Persistit Corporation.
 *
 * PERSISTIT CORPORATION MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE
 * SUITABILITY OF THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR
 * A PARTICULAR PURPOSE, OR NON-INFRINGEMENT. PERSISTIT CORPORATION SHALL
 * NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING,
 * MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

package com.persistit.stress;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.persistit.ArgParser;
import com.persistit.Exchange;
import com.persistit.Transaction;
import com.persistit.exception.RollbackException;

/**
 * This class tests recovery after a non-graceful shutdown. To test, run this
 * class, then stop it by: (a) normal shutdown, (b) kill -9, or (c) power-off,
 * (d) pulling a disk from its bay.
 * 
 * The test writes a sequence of transactions in pseudo-random but predictable
 * order. As soon as each transaction commits, a "ticket" is written to stdout.
 * The stream of tickets can be redirected either to a file (for (a) and (b)) or
 * to a terminal session on second computer (for (c) and (d)) so that after
 * recovering the victim Persistit instance, a nearly complete set of
 * transaction tickets is available. Note that because of network latency and/or
 * buffering the ticket stream will be nearly complete but may be a fraction of
 * a second behind what actually happened on the victim.
 * 
 * Tickets are produced by incrementing an AtomicLong object. The AtomicLong is
 * static so that multiple threads running this test acquire strictly unique,
 * increasing ticket values.
 * 
 * After recovery, the ticket stream is run through the verify method of this
 * class to confirm that every transaction whose ticket was record is actually
 * present in the database. This imposes
 * 
 * This class offers a framework for testing different sizes and types of
 * transactions. Each transaction type registers itself with the transaction
 * scheduler.
 * 
 * This class performs randomized transactions, but according to predictable
 * schedule. As each transaction is committed, this class emits a ticket to
 * stdout. A driver program records the stream of tickets and then feeds them to
 * a verification step to ensure that every transaction this class said it has
 * committed is actually represented in the database.
 * 
 * 
 * @author peter
 * 
 */
public class StressRecovery extends StressBase {

    private final static String SHORT_DESCRIPTION = "Issues a series of transactions with external logging";

    private final static String LONG_DESCRIPTION = "   Execute transactions in single- or multi-threaded pattern: \r\n"
            + "    write progress to stdout.  This can be recorded, and played back to ensure that the"
            + "    resulting database after recovery contains all the committed transactions \r\n";

    private final static String[] ARGS_TEMPLATE = {
            "size|int:30:1:20000|Maximum size of each data value",
            "verify|String:|Path name of ticket list to verify",
            "latency|long:0:0:60000|Maximum acceptable fault latency" };

    private final static AtomicLong ticketSequence = new AtomicLong();

    private final ArrayList<TransactionType> registry = new ArrayList<TransactionType>();

    int _size;
    int _splay;
    boolean _verifyMode;
    String _verifyPath;
    long _maxLatency;
    BufferedReader _verifyReader;

    interface TransactionType {
        /**
         * Given a ticketId, perform a transaction which can later be verified.
         * If this method returns without throwing an Exception, then the
         * transaction must be committed.
         * 
         * @param ticketId
         * @throws Exception
         */
        void performTransaction(final long ticketId) throws Exception;

        /**
         * Given a ticketId, verify that the transaction previously performed by
         * {@link #performTransaction(long)} is present in the database.
         * 
         * @param ticketId
         * @throws Exception
         */
        void verifyTransaction(final long ticketId) throws Exception;
    }

    private static class IntentionalException extends Exception {
        /**
         * 
         */
        private static final long serialVersionUID = 1L;
        // intended to be ignored
    }

    @Override
    public String shortDescription() {
        return SHORT_DESCRIPTION;
    }

    @Override
    public String longDescription() {
        return LONG_DESCRIPTION;
    }

    @Override
    public void setUp() throws Exception {
        _ap = new ArgParser("com.persistit.StressRecovery", _args,
                ARGS_TEMPLATE);
        _size = _ap.getIntValue("size");
        _maxLatency = _ap.getLongValue("latency") * 1000000l;
        _verifyPath = _ap.getStringValue("verify");
        if (_verifyPath != null && !_verifyPath.isEmpty()) {
            _verifyMode = true;
            _verifyReader = new BufferedReader(new FileReader(_verifyPath));
        }
        _dotGranularity = 10000;

        super.setUp(!_verifyMode);
        try {
            // Exchange with shared Tree
            _exs = getPersistit().getExchange("persistit", "shared", true);
            // Exchange with Thread-private Tree
            _ex = getPersistit().getExchange("persistit",
                    _rootName + _threadIndex, true);
        } catch (final Exception ex) {
            handleThrowable(ex);
        }
        // registry.add(new SimpleTransactionType());
        registry.add(new IndexSimulationTransactionType());
    }

    @Override
    public void executeTest() throws IOException {
        final long zero = System.nanoTime();
        long firstFault = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        if (_verifyMode) {
            int faults = 0;
            for (int _count = 1;; _count++) {
                String line = "~not read~";
                long ticketId = -1;
                long start = -1;
                long elapsed = -1;
                try {
                    line = _verifyReader.readLine();
                    if (line == null) {
                        break;
                    }
                    if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                        continue;
                    }
                    final String[] s = line.split(",");
                    if (s.length != 3) {
                        continue;
                    }
                    ticketId = Long.parseLong(s[0]);
                    start = Long.parseLong(s[1]);
                    elapsed = Long.parseLong(s[2]);
                    last = Math.max(last, start + elapsed);
                } catch (Exception e) {
                    fail(e + " while reading line " + _count + " of "
                            + _verifyPath + ": " + line);
                }
                if (elapsed >= 0) {
                    try {
                        TransactionType tt = registry
                                .get((int) (ticketId % registry.size()));
                        tt.verifyTransaction(ticketId);
                    } catch (Exception e) {
                        firstFault = Math.min(firstFault, start + elapsed);
                        faults++;
                    }
                }
            }

            if (faults > 0) {
                if (last - firstFault < _maxLatency) {
                    printf("There were %,d faults. Last one occurred %,dms before crash - \n"
                            + "acceptable because acceptable latency setting is %,dms.",
                            faults, (last - firstFault) / 1000000l,
                            _maxLatency / 1000000l);
                } else {
                    fail("Verification encountered " + faults + " faults");
                }
            }
        } else {
            //
            // forever since this test is intended to be interrupted by
            // a shutdown or crash.
            //
            for (int _count = 1;; _count++) {
                final long ticketId = ticketSequence.incrementAndGet();
                final TransactionType tt = registry
                        .get((int) (ticketId % registry.size()));
                final long start = System.nanoTime();
                try {
                    tt.performTransaction(ticketId);
                    final long now = System.nanoTime();
                    emit(ticketId, start - zero, now - start);
                } catch (IntentionalException e) {
                    emit(ticketId, start - zero, -1);
                } catch (Exception e) {
                    emit(ticketId, start - zero, -1);
                    printStackTrace(e);
                }
            }
        }
    }

    private synchronized static void emit(final long ticketId,
            final long start, final long elapsed) {
        System.out.println(ticketId + "," + start + "," + elapsed);
        System.out.flush();
    }

    int keyInteger(final int counter) {
        int keyInteger = (counter * _splay) % _total;
        if (keyInteger < 0) {
            keyInteger += _total;
        }
        return keyInteger;
    }

    class SimpleTransactionType implements TransactionType {

        @Override
        public void performTransaction(long ticketId) throws Exception {
            Transaction txn = _persistit.getTransaction();
            for (;;) {
                txn.begin();
                try {
                    _exs.getValue().putString("ticket " + ticketId + " value");
                    _exs.clear().append(ticketId % 1000)
                            .append(ticketId / 1000);
                    _exs.store();
                    txn.commit(false);
                    break;
                } catch (RollbackException e) {
                    continue;
                } finally {
                    txn.end();
                }
            }
        }

        @Override
        public void verifyTransaction(long ticketId) throws Exception {
            _exs.clear().append(ticketId % 1000).append(ticketId / 1000);
            _exs.fetch();
            check(ticketId, _exs, "ticket " + ticketId + " value");
        }
    }

    class IndexSimulationTransactionType implements TransactionType {

        final String[] FRAGMENTS = { "now", "is", "the", "time", "for", "a",
                "quick", "brown", "fox", "to", "come", "aid", "some", "party" };

        @Override
        public void performTransaction(long ticketId) throws Exception {
            final StringBuilder sb = new StringBuilder(String.format("%,15d",
                    ticketId));
            final int size = (int) ((ticketId * 17) % 876);
            for (int i = 0; i < size; i++) {
                sb.append('-');
            }
            Transaction txn = _persistit.getTransaction();
            for (;;) {
                txn.begin();
                try {
                    _exs.getValue().putString(sb);
                    long t = ticketId;
                    _exs.clear();
                    while (t != 0) {
                        _exs.getKey().append(
                                FRAGMENTS[(int) (t) % FRAGMENTS.length]);
                        t /= FRAGMENTS.length;
                    }
                    _exs.store();

                    _exs.getValue().putString("$$$");
                    _exs.clear().append(1);
                    t = ticketId * 11;
                    while (t != 0) {
                        _exs.getKey().append(
                                FRAGMENTS[(int) (t) % FRAGMENTS.length]);
                        t /= FRAGMENTS.length;
                    }
                    _exs.store();

                    _exs.clear().append(2);
                    t = ticketId * 13;
                    while (t != 0) {
                        _exs.getKey().append(
                                FRAGMENTS[(int) (t) % FRAGMENTS.length]);
                        t /= FRAGMENTS.length;
                    }
                    _exs.store();

                    txn.commit(true);
                    break;
                } catch (RollbackException e) {
                    continue;
                } finally {
                    txn.end();
                }
            }
        }

        @Override
        public void verifyTransaction(long ticketId) throws Exception {

            long t = ticketId;
            _exs.clear();
            while (t != 0) {
                _exs.getKey().append(FRAGMENTS[(int) (t) % FRAGMENTS.length]);
                t /= FRAGMENTS.length;
            }
            _exs.fetch();
            check(ticketId, _exs, String.format("%,15d", ticketId));

            _exs.clear().append(1);
            t = ticketId * 11;
            while (t != 0) {
                _exs.getKey().append(FRAGMENTS[(int) (t) % FRAGMENTS.length]);
                t /= FRAGMENTS.length;
            }
            _exs.fetch();
            check(ticketId, _exs, "$$$");

            _exs.clear().append(2);
            t = ticketId * 13;
            while (t != 0) {
                _exs.getKey().append(FRAGMENTS[(int) (t) % FRAGMENTS.length]);
                t /= FRAGMENTS.length;
            }
            _exs.fetch();
            check(ticketId, _exs, "$$$");

        }
    }

    private void check(final long ticketId, final Exchange ex,
            final String expected) throws Exception {
        ex.fetch();
        if (!ex.getValue().isDefined()) {
            throw new RuntimeException("Ticket " + ticketId
                    + " missing value at " + ex.getKey());
        }
        final String s = ex.getValue().getString();
        if (!s.startsWith(expected)) {
            throw new RuntimeException("Ticket " + ticketId
                    + " incorrect value at " + ex.getKey());
        }

    }

    public static void main(final String[] args) {
        final StressRecovery test = new StressRecovery();
        test.runStandalone(args);
    }

}