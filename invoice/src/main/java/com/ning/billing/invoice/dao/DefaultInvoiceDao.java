/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.invoice.dao;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ning.billing.entitlement.api.billing.EntitlementBillingApi;
import com.ning.billing.invoice.model.FixedPriceInvoiceItem;
import com.ning.billing.invoice.model.RecurringInvoiceItem;
import org.joda.time.DateTime;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.Transaction;
import org.skife.jdbi.v2.TransactionStatus;
import com.google.inject.Inject;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceCreationNotification;
import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.invoice.api.InvoicePayment;
import com.ning.billing.invoice.api.user.DefaultInvoiceCreationNotification;
import com.ning.billing.invoice.notification.NextBillingDateNotifier;
import com.ning.billing.util.bus.Bus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultInvoiceDao implements InvoiceDao {
    private final static Logger log = LoggerFactory.getLogger(DefaultInvoiceDao.class);

    private final InvoiceSqlDao invoiceSqlDao;
    private final RecurringInvoiceItemSqlDao recurringInvoiceItemSqlDao;
    //private final FixedPriceInvoiceItemSqlDao fixedPriceInvoiceItemSqlDao;
    private final InvoicePaymentSqlDao invoicePaymentSqlDao;
    private final NextBillingDateNotifier notifier;
    private final EntitlementBillingApi entitlementBillingApi;

    private final Bus eventBus;

    @Inject
    public DefaultInvoiceDao(final IDBI dbi, final Bus eventBus,
                             final NextBillingDateNotifier notifier, final EntitlementBillingApi entitlementBillingApi) {
        this.invoiceSqlDao = dbi.onDemand(InvoiceSqlDao.class);
        this.recurringInvoiceItemSqlDao = dbi.onDemand(RecurringInvoiceItemSqlDao.class);
        //this.fixedPriceInvoiceItemSqlDao = dbi.onDemand(FixedPriceInvoiceItemSqlDao.class);
        this.invoicePaymentSqlDao = dbi.onDemand(InvoicePaymentSqlDao.class);
        this.eventBus = eventBus;
        this.notifier = notifier;
        this.entitlementBillingApi = entitlementBillingApi;
    }

    @Override
    public List<Invoice> getInvoicesByAccount(final UUID accountId) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                List<Invoice> invoices = invoiceDao.getInvoicesByAccount(accountId.toString());

                getInvoiceItemsWithinTransaction(invoices, invoiceDao);
                getInvoicePaymentsWithinTransaction(invoices, invoiceDao);

                return invoices;
            }
        });
    }

    @Override
    public List<Invoice> getInvoicesByAccount(final UUID accountId, final DateTime fromDate) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                List<Invoice> invoices = invoiceDao.getInvoicesByAccountAfterDate(accountId.toString(), fromDate.toDate());

                getInvoiceItemsWithinTransaction(invoices, invoiceDao);
                getInvoicePaymentsWithinTransaction(invoices, invoiceDao);

                return invoices;
            }
        });
    }

    @Override
    public List<InvoiceItem> getInvoiceItemsByAccount(final UUID accountId) {
        return recurringInvoiceItemSqlDao.getInvoiceItemsByAccount(accountId.toString());
    }

    @Override
    public List<Invoice> get() {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
             @Override
             public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                 List<Invoice> invoices = invoiceDao.get();

                 getInvoiceItemsWithinTransaction(invoices, invoiceDao);
                 getInvoicePaymentsWithinTransaction(invoices, invoiceDao);

                 return invoices;
             }
        });
    }

    @Override
    public Invoice getById(final UUID invoiceId) {
        return invoiceSqlDao.inTransaction(new Transaction<Invoice, InvoiceSqlDao>() {
             @Override
             public Invoice inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                 Invoice invoice = invoiceDao.getById(invoiceId.toString());

                 if (invoice != null) {
                     getInvoiceItemsWithinTransaction(invoice, invoiceDao);
                     getInvoicePaymentsWithinTransaction(invoice, invoiceDao);
                 }

                 return invoice;
             }
        });
    }

    @Override
    public void create(final Invoice invoice) {
        invoiceSqlDao.inTransaction(new Transaction<Void, InvoiceSqlDao>() {
            @Override
            public Void inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {

                // STEPH this seems useless
                Invoice currentInvoice = invoiceDao.getById(invoice.getId().toString());

                if (currentInvoice == null) {
                    invoiceDao.create(invoice);

                    List<InvoiceItem> recurringInvoiceItems = invoice.getInvoiceItems(RecurringInvoiceItem.class);
                    RecurringInvoiceItemSqlDao recurringInvoiceItemDao = invoiceDao.become(RecurringInvoiceItemSqlDao.class);
                    recurringInvoiceItemDao.batchCreateFromTransaction(recurringInvoiceItems);

                    notifyOfFutureBillingEvents(invoiceSqlDao, recurringInvoiceItems);
                    setChargedThroughDates(invoiceSqlDao, recurringInvoiceItems);

                    List<InvoiceItem> fixedPriceInvoiceItems = invoice.getInvoiceItems(FixedPriceInvoiceItem.class);
                    FixedPriceInvoiceItemSqlDao fixedPriceInvoiceItemDao = invoiceDao.become(FixedPriceInvoiceItemSqlDao.class);
                    fixedPriceInvoiceItemDao.batchCreateFromTransaction(fixedPriceInvoiceItems);

                    // STEPH Why do we need that? Are the payments not always null at this point?
                    List<InvoicePayment> invoicePayments = invoice.getPayments();
                    InvoicePaymentSqlDao invoicePaymentSqlDao = invoiceDao.become(InvoicePaymentSqlDao.class);
                    invoicePaymentSqlDao.batchCreateFromTransaction(invoicePayments);

                    InvoiceCreationNotification event;
                    event = new DefaultInvoiceCreationNotification(invoice.getId(), invoice.getAccountId(),
                                                                  invoice.getBalance(), invoice.getCurrency(),
                                                                  invoice.getInvoiceDate());
                    eventBus.postFromTransaction(event, invoiceDao);
                }

                return null;
            }
        });
    }

    @Override
    public List<Invoice> getInvoicesBySubscription(final UUID subscriptionId) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                List<Invoice> invoices = invoiceDao.getInvoicesBySubscription(subscriptionId.toString());

                getInvoiceItemsWithinTransaction(invoices, invoiceDao);
                getInvoicePaymentsWithinTransaction(invoices, invoiceDao);

                return invoices;
            }
        });
    }

    @Override
    public List<UUID> getInvoicesForPayment(final DateTime targetDate, final int numberOfDays) {
        return invoiceSqlDao.getInvoicesForPayment(targetDate.toDate(), numberOfDays);
    }

    @Override
    public BigDecimal getAccountBalance(final UUID accountId) {
        return invoiceSqlDao.getAccountBalance(accountId.toString());
    }

    @Override
    public void notifyOfPaymentAttempt(InvoicePayment invoicePayment) {
        invoicePaymentSqlDao.notifyOfPaymentAttempt(invoicePayment);
    }

    @Override
    public List<Invoice> getUnpaidInvoicesByAccountId(final UUID accountId, final DateTime upToDate) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                List<Invoice> invoices = invoiceSqlDao.getUnpaidInvoicesByAccountId(accountId.toString(), upToDate.toDate());

                getInvoiceItemsWithinTransaction(invoices, invoiceDao);
                getInvoicePaymentsWithinTransaction(invoices, invoiceDao);

                return invoices;
            }
        });
    }

    @Override
    public UUID getInvoiceIdByPaymentAttemptId(UUID paymentAttemptId) {
        return invoiceSqlDao.getInvoiceIdByPaymentAttemptId(paymentAttemptId.toString());
    }

    @Override
    public InvoicePayment getInvoicePayment(UUID paymentAttemptId) {
        return invoicePaymentSqlDao.getInvoicePayment(paymentAttemptId);
    }

    @Override
    public void test() {
        invoiceSqlDao.test();
    }

    private void getInvoiceItemsWithinTransaction(final List<Invoice> invoices, final InvoiceSqlDao invoiceDao) {
        for (final Invoice invoice : invoices) {
            getInvoiceItemsWithinTransaction(invoice, invoiceDao);
        }
    }

    private void getInvoiceItemsWithinTransaction(final Invoice invoice, final InvoiceSqlDao invoiceDao) {
        RecurringInvoiceItemSqlDao recurringInvoiceItemDao = invoiceDao.become(RecurringInvoiceItemSqlDao.class);
        List<InvoiceItem> recurringInvoiceItems = recurringInvoiceItemDao.getInvoiceItemsByInvoice(invoice.getId().toString());
        invoice.addInvoiceItems(recurringInvoiceItems);

        FixedPriceInvoiceItemSqlDao fixedPriceInvoiceItemDao = invoiceDao.become(FixedPriceInvoiceItemSqlDao.class);
        List<InvoiceItem> fixedPriceInvoiceItems = fixedPriceInvoiceItemDao.getInvoiceItemsByInvoice(invoice.getId().toString());
        invoice.addInvoiceItems(fixedPriceInvoiceItems);
    }

    private void getInvoicePaymentsWithinTransaction(final List<Invoice> invoices, final InvoiceSqlDao invoiceDao) {
        for (Invoice invoice : invoices) {
            getInvoicePaymentsWithinTransaction(invoice, invoiceDao);
        }
    }

    private void getInvoicePaymentsWithinTransaction(final Invoice invoice, final InvoiceSqlDao invoiceDao) {
        InvoicePaymentSqlDao invoicePaymentSqlDao = invoiceDao.become(InvoicePaymentSqlDao.class);
        String invoiceId = invoice.getId().toString();
        List<InvoicePayment> invoicePayments = invoicePaymentSqlDao.getPaymentsForInvoice(invoiceId);
        invoice.addPayments(invoicePayments);
    }

    private void notifyOfFutureBillingEvents(final InvoiceSqlDao dao, final List<InvoiceItem> invoiceItems) {
        for (final InvoiceItem item : invoiceItems) {
            if (item instanceof RecurringInvoiceItem) {
                RecurringInvoiceItem recurringInvoiceItem = (RecurringInvoiceItem) item;
                if ((recurringInvoiceItem.getEndDate() != null) &&
                        (recurringInvoiceItem.getAmount() == null ||
                                recurringInvoiceItem.getAmount().compareTo(BigDecimal.ZERO) >= 0)) {
                notifier.insertNextBillingNotification(dao, item.getSubscriptionId(), recurringInvoiceItem.getEndDate());
                }
            }
        }
    }

    private void setChargedThroughDates(final InvoiceSqlDao dao, final Collection<InvoiceItem> invoiceItems) {
        for (InvoiceItem item : invoiceItems) {
            if (item instanceof RecurringInvoiceItem) {
                RecurringInvoiceItem recurringInvoiceItem = (RecurringInvoiceItem) item;
                if ((recurringInvoiceItem.getEndDate() != null) &&
                        (recurringInvoiceItem.getAmount() == null ||
                                recurringInvoiceItem.getAmount().compareTo(BigDecimal.ZERO) >= 0)) {
                    log.info("Setting CTD for invoice item {} to {}", recurringInvoiceItem.getId().toString(), recurringInvoiceItem.getEndDate().toString());
                    entitlementBillingApi.setChargedThroughDateFromTransaction(dao, recurringInvoiceItem.getSubscriptionId(), recurringInvoiceItem.getEndDate());
                }
            }
        }
    }
}
