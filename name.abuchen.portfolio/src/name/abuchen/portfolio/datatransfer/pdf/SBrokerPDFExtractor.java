package name.abuchen.portfolio.datatransfer.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Block;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Transaction;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.util.TextUtil;

@SuppressWarnings("nls")
public class SBrokerPDFExtractor extends AbstractPDFExtractor
{
    private static final String EXCHANGE_RATE = "exchangeRate"; //$NON-NLS-1$
    private static final String FLAG_WITHHOLDING_TAX_FOUND  = "isHoldingTax"; //$NON-NLS-1$

    public SBrokerPDFExtractor(Client client)
    {
        super(client);

        addBankIdentifier("S Broker AG & Co. KG"); //$NON-NLS-1$
        addBankIdentifier("Sparkasse"); //$NON-NLS-1$

        addBuySellTransaction();
        addDividendTransaction();
    }

    @Override
    public String getLabel()
    {
        return "S Broker AG & Co. KG / Sparkasse"; //$NON-NLS-1$
    }

    private void addBuySellTransaction()
    {
        DocumentType type = new DocumentType("(Kauf(.*)?|Verkauf(.*)?|Wertpapier Abrechnung Ausgabe Investmentfonds)");
        this.addDocumentTyp(type);

        Transaction<BuySellEntry> pdfTransaction = new Transaction<>();
        pdfTransaction.subject(() -> {
            BuySellEntry entry = new BuySellEntry();
            entry.setType(PortfolioTransaction.Type.BUY);
            return entry;
        });

        Block firstRelevantLine = new Block("^(Kauf(.*)?|Verkauf(.*)?|Wertpapier Abrechnung Ausgabe Investmentfonds)$");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
                // Is type --> "Verkauf" change from BUY to SELL
                .section("type").optional()
                .match("^(?<type>(Kauf|Verkauf))(.*)?$")
                .assign((t, v) -> {
                    if (v.get("type").equals("Verkauf"))
                    {
                        t.setType(PortfolioTransaction.Type.SELL);
                    }

                    /***
                     * If we have multiple entries in the document,
                     * with taxes and tax refunds,
                     * then the "negative" flag must be removed.
                     */
                    type.getCurrentContext().remove("negative");
                })

                // Gattungsbezeichnung ISIN
                // iS.EO G.B.C.1.5-10.5y.U.ETF DE Inhaber-Anteile DE000A0H0785
                .section("isin", "name").optional()
                .match("^Gattungsbezeichnung ISIN$")
                .match("^(?<name>.*) (?<isin>[\\w]{12})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // Stück 7,1535 BGF - WORLD TECHNOLOGY FUND LU0171310443 (A0BMAN)
                .section("shares", "name", "isin", "wkn").optional()
                .match("^Nominale Wertpapierbezeichnung ISIN \\(WKN\\)$")
                .match("^St.ck (?<shares>[\\.,\\d]+) (?<name>.*) (?<isin>[\\w]{12}) \\((?<wkn>.*)\\)$")
                .assign((t, v) -> {
                    t.setShares(asShares(v.get("shares")));
                    t.setSecurity(getOrCreateSecurity(v));
                })

                // STK 16,000 EUR 120,4000
                .section("shares").optional()
                .match("^STK (?<shares>[\\.,\\d]+) .*$")
                .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                .oneOf(
                                // Auftrag vom 27.02.2021 01:31:42 Uhr
                                section -> section
                                        .attributes("date", "time")
                                        .match("^Auftrag vom (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<time>[\\d]{2}:[\\d]{2}:[\\d]{2}).*$")
                                        .assign((t, v) -> t.setDate(asDate(v.get("date"), v.get("time"))))
                                ,
                                // Handelstag 05.05.2021 EUR 498,20-
                                // Handelszeit 09:04
                                section -> section
                                        .attributes("date", "time")
                                        .match("^Handelstag (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                                        .match("^Handelszeit (?<time>[\\d]{2}:[\\d]{2})(.*)?$")
                                        .assign((t, v) -> t.setDate(asDate(v.get("date"), v.get("time"))))
                        )

                .oneOf(
                                // Ausmachender Betrag 500,00- EUR
                                section -> section
                                        .attributes("amount", "currency")
                                        .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+)(\\-)? (?<currency>[\\w]{3})$")
                                        .assign((t, v) -> {
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(v.get("currency"));
                                        })
                                ,
                                // Wert Konto-Nr. Betrag zu Ihren Lasten
                                // 01.10.2014 10/0000/000 EUR 1.930,17
                                section -> section
                                        .attributes("currency", "amount")
                                        .match("^Wert Konto-Nr. Betrag zu Ihren (Gunsten|Lasten).*$")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} [\\/\\d]+ (?<currency>[\\w]{3}) (?<amount>[\\.,\\d]+)$")
                                        .assign((t, v) -> {
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(v.get("currency"));
                                        })
                        )

                .wrap(BuySellEntryItem::new);

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);
        addTaxReturnBlock(type);
    }

    private void addDividendTransaction()
    {
        DocumentType type = new DocumentType("Dividendengutschrift|Aussch.ttung");
        this.addDocumentTyp(type);

        Block block = new Block("^(Dividendengutschrift|Aussch.ttung (f.r|Investmentfonds))(.*)?$");
        type.addBlock(block);
        Transaction<AccountTransaction> pdfTransaction = new Transaction<>();

        pdfTransaction.subject(() -> {
            AccountTransaction entry = new AccountTransaction();
            entry.setType(AccountTransaction.Type.DIVIDENDS);

            /***
             * If we have multiple entries in the document,
             * with taxes and tax refunds,
             * then the "negative" flag must be removed.
             */
            type.getCurrentContext().remove("negative");

            return entry;
        });

        pdfTransaction
                // Gattungsbezeichnung ISIN
                // iS.EO G.B.C.1.5-10.5y.U.ETF DE Inhaber-Anteile DE000A0H0785
                .section("isin", "name").optional()
                .find("^Gattungsbezeichnung ISIN$")
                .match("^(?<name>.*) (?<isin>[\\w]{12})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // Stück 250 GLADSTONE COMMERCIAL CORP. US3765361080 (260884)
                // REGISTERED SHARES DL -,01
                // Zahlbarkeitstag 31.12.2021 Ausschüttung pro St. 0,125275000 USD
                .section("shares", "name", "isin", "wkn", "name1", "currency").optional()
                .match("^St.ck (?<shares>[\\.,\\d]+) (?<name>.*) (?<isin>[\\w]{12}) \\((?<wkn>.*)\\)$")
                .match("^(?<name1>.*)$")
                .match("^Zahlbarkeitstag [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} Aussch.ttung pro St\\. [\\.,\\d]+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    if (!v.get("name1").startsWith("Zahlbarkeitstag"))
                        v.put("name", TextUtil.strip(v.get("name")) + " " + TextUtil.strip(v.get("name1")));

                    t.setShares(asShares(v.get("shares")));
                    t.setSecurity(getOrCreateSecurity(v));
                })

                .oneOf(
                                // STK 16,000 17.11.2014 17.11.2014 EUR 0,793806
                                section -> section
                                        .attributes("date")
                                        .match("^STK [\\.,\\d]+ [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                                        .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))
                                ,
                                // Zahlbarkeitstag 31.12.2021 Ausschüttung pro St. 0,125275000 USD
                                section -> section
                                        .attributes("date")
                                        .match("^Zahlbarkeitstag (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                                        .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))
                        )

                .oneOf(
                                // STK 16,000 17.11.2014 17.11.2014 EUR 0,793806
                                section -> section
                                        .attributes("shares")
                                        .match("^STK (?<shares>[\\.,\\d]+) [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} .*$")
                                        .assign((t, v) -> t.setShares(asShares(v.get("shares"))))
                                ,
                                // Stück 250 GLADSTONE COMMERCIAL CORP. US3765361080 (260884)
                                section -> section
                                        .attributes("shares")
                                        .match("^St.ck (?<shares>[\\.,\\d]+) .*$")
                                        .assign((t, v) -> t.setShares(asShares(v.get("shares"))))
                        )

                .oneOf(
                                // Wert Konto-Nr. Betrag zu Ihren Gunsten
                                // 17.11.2014 10/0000/000 EUR 12,70
                                section -> section
                                        .attributes("currency", "amount")
                                        .find("Wert Konto\\-Nr\\. Betrag zu Ihren Gunsten")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} .* (?<currency>[\\w]{3}) (?<amount>[\\.,\\d]+)$")
                                        .assign((t, v) -> {
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                                        })
                                ,
                                // Wert Konto-Nr. Devisenkurs Betrag zu Ihren Gunsten
                                // 15.12.2014 12/3456/789 EUR/USD 1,24495 EUR 52,36
                                section -> section
                                        .attributes("currency", "amount")
                                        .find("Wert Konto\\-Nr\\. Devisenkurs Betrag zu Ihren Gunsten")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} .* (?<currency>[\\w]{3}) (?<amount>[\\.,\\d]+)$")
                                        .assign((t, v) -> {
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                                        })
                                ,
                                // Ausmachender Betrag 20,31+ EUR
                                section -> section
                                        .attributes("amount", "currency")
                                        .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+)\\+ (?<currency>[\\w]{3})$")
                                        .assign((t, v) -> {
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                                        })
                        )

                // Devisenkurs EUR / PLN 4,5044
                // Ausschüttung 31,32 USD 27,48+ EUR
                .section("exchangeRate", "fxAmount", "fxCurrency", "amount", "currency").optional()
                .match("^Devisenkurs [\\w]{3} \\/ [\\w]{3} ([\\s]+)?(?<exchangeRate>[\\.,\\d]+)$")
                .match("^(Dividendengutschrift|Aussch.ttung) (?<fxAmount>[\\.,\\d]+) (?<fxCurrency>[\\w]{3}) (?<amount>[\\.,\\d]+)\\+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    BigDecimal exchangeRate = asExchangeRate(v.get("exchangeRate"));
                    if (t.getCurrencyCode().contentEquals(asCurrencyCode(v.get("fxCurrency"))))
                    {
                        exchangeRate = BigDecimal.ONE.divide(exchangeRate, 10, RoundingMode.HALF_DOWN);
                    }
                    type.getCurrentContext().put("exchangeRate", exchangeRate.toPlainString());

                    if (!t.getCurrencyCode().equals(t.getSecurity().getCurrencyCode()))
                    {
                        BigDecimal inverseRate = BigDecimal.ONE.divide(exchangeRate, 10,
                                        RoundingMode.HALF_DOWN);

                        // check, if forex currency is transaction
                        // currency or not and swap amount, if necessary
                        Unit grossValue;
                        if (!asCurrencyCode(v.get("fxCurrency")).equals(t.getCurrencyCode()))
                        {
                            Money fxAmount = Money.of(asCurrencyCode(v.get("fxCurrency")),
                                            asAmount(v.get("fxAmount")));
                            Money amount = Money.of(asCurrencyCode(v.get("currency")),
                                            asAmount(v.get("amount")));
                            grossValue = new Unit(Unit.Type.GROSS_VALUE, amount, fxAmount, inverseRate);
                        }
                        else
                        {
                            Money amount = Money.of(asCurrencyCode(v.get("fxCurrency")),
                                            asAmount(v.get("fxAmount")));
                            Money fxAmount = Money.of(asCurrencyCode(v.get("currency")),
                                            asAmount(v.get("amount")));
                            grossValue = new Unit(Unit.Type.GROSS_VALUE, amount, fxAmount, inverseRate);
                        }
                        t.addUnit(grossValue);
                    }
                })

                // Wert Konto-Nr. Devisenkurs Betrag zu Ihren Gunsten
                // 15.12.2014 12/3456/789 EUR/USD 1,24495 EUR 52,36
                .section("exchangeRate").optional()
                .find("Wert Konto\\-Nr\\. Devisenkurs Betrag zu Ihren Gunsten")
                .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} .* [\\w]{3}\\/[\\w]{3} (?<exchangeRate>[\\.,\\d]+) [\\w]{3} [\\.,\\d]+$")
                .assign((t, v) -> {
                    BigDecimal exchangeRate = asExchangeRate(v.get("exchangeRate"));
                    type.getCurrentContext().put("exchangeRate", exchangeRate.toPlainString());
                })

                .wrap(t -> new TransactionItem(t));

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);

        block.set(pdfTransaction);
    }

    private void addTaxReturnBlock(DocumentType type)
    {
        Block block = new Block("^(Kauf(.*)?|Verkauf(.*)?|Wertpapier Abrechnung Ausgabe Investmentfonds)$");
        type.addBlock(block);
        block.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction transaction = new AccountTransaction();
                    transaction.setType(AccountTransaction.Type.TAX_REFUND);
                    return transaction;
                })

                // Gattungsbezeichnung ISIN
                // iS.EO G.B.C.1.5-10.5y.U.ETF DE Inhaber-Anteile DE000A0H0785
                .section("isin", "name").optional()
                .match("^Gattungsbezeichnung ISIN$")
                .match("^(?<name>.*) (?<isin>[\\w]{12})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))
                                

                // Wert Konto-Nr. Abrechnungs-Nr. Betrag zu Ihren Gunsten
                // 03.06.2015 10/3874/009 87966195 EUR 11,48
                .section("date", "amount", "currency").optional()
                .match("^Wert Konto-Nr\\. Abrechnungs\\-Nr\\. Betrag zu Ihren Gunsten$")
                .match("^(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) [\\/\\d]+ [\\d]+ (?<currency>[\\w]{3}) (?<amount>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(v.get("date")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                })

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                }));
    }

    private <T extends Transaction<?>> void addTaxesSectionsTransaction(T transaction, DocumentType type)
    {
        /***
         * if we have a tax refunds,
         * we set a flag and don't book tax below
         */
        transaction
                .section("n").optional()
                .match("zu versteuern \\(negativ\\) (?<n>.*)")
                .assign((t, v) -> type.getCurrentContext().put("negative", "X"));

        transaction
                // einbehaltene Kapitalertragsteuer EUR 7,03
                .section("tax", "currency").optional()
                .match("^einbehaltene Kapitalertragsteuer (?<currency>[\\w]{3}) (?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Kapitalertragsteuer 24,51 % auf 11,00 EUR 2,70- EUR
                .section("tax", "currency").optional()
                .match("^Kapitalertragsteuer [\\.,\\d]+ % .* [\\.,\\d]+ [\\w]{3} (?<tax>[\\.,\\d]+)\\- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Kapitalertragsteuer EUR 70,16
                .section("tax", "currency").optional()
                .match("^Kapitalertragsteuer (?<currency>[\\w]{3}) (?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // einbehaltener Solidaritätszuschlag EUR 0,38
                .section("tax", "currency").optional()
                .match("^einbehaltener Solidarit.tszuschlag (?<currency>[\\w]{3}) (?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Solidaritätszuschlag EUR 3,86
                .section("tax", "currency").optional()
                .match("^Solidarit.tszuschlag (?<currency>[\\w]{3}) (?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Solidaritätszuschlag 5,5 % auf 2,70 EUR 0,14- EUR
                .section("tax", "currency").optional()
                .match("^Solidarit.tszuschlag [\\.,\\d]+ % .* [\\.,\\d]+ [\\w]{3} (?<tax>[\\.,\\d]+)\\- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // einbehaltener Kirchensteuer EUR 1,00
                .section("tax", "currency").optional()
                .match("^einbehaltener Kirchensteuer (?<currency>[\\w]{3}) (?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Kirchensteuer EUR 1,00
                .section("tax", "currency").optional()
                .match("^Kirchensteuer (?<currency>[\\w]{3}) (?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Kirchensteuer 8 % auf 2,70 EUR 0,21- EUR
                .section("tax", "currency").optional()
                .match("^Kirchensteuer [\\.,\\d]+ % .* [\\.,\\d]+ [\\w]{3} (?<tax>[\\.,\\d]+)\\- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Einbehaltene Quellensteuer 15 % auf 31,32 USD 4,12- EUR
                .section("quellensteinbeh", "currency").optional()
                .match("^Einbehaltene Quellensteuer .* (?<quellensteinbeh>[\\.,\\d]+)\\- (?<currency>[\\w]{3})$")
                .assign((t, v) ->  {
                    type.getCurrentContext().put(FLAG_WITHHOLDING_TAX_FOUND, Boolean.TRUE.toString());
                    addTax(t, v, type, "quellensteinbeh");
                })

                // Anrechenbare Quellensteuer pro Stück 0,01879125 USD 4,70 USD
                .section("quellenstanr", "currency").optional()
                .match("^Anrechenbare Quellensteuer .* (?<quellenstanr>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> addTax(t, v, type, "quellenstanr"))

                // davon anrechenbare US-Quellensteuer 15% USD 13,13
                .section("tax", "currency").optional()
                .match("^davon anrechenbare US-Quellensteuer [\\.,\\d]+% (?<currency>[\\w]{3}) (?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    if (!"X".equals(type.getCurrentContext().get("negative")))
                    {
                        processTaxEntries(t, v, type);
                    }
                });
    }

    private <T extends Transaction<?>> void addFeesSectionsTransaction(T transaction, DocumentType type)
    {
        transaction
                // Handelszeit 09:02 Orderentgelt                EUR 10,90-
                .section("fee", "currency").optional()
                .match(".* Orderentgelt ([\\s]+)?(?<currency>[\\w]{3}) (?<fee>[\\.,\\d]+)-")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Orderentgelt
                // EUR 0,71-
                .section("fee", "currency").optional()
                .match("^Orderentgelt$")
                .match("^(?<currency>[\\w]{3}) (?<fee>[\\.,\\d]+)-$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Börse Stuttgart Börsengebühr EUR 2,29-
                .section("fee", "currency").optional()
                .match(".* B.rsengeb.hr (?<currency>[\\w]{3}) (?<fee>[\\.,\\d]+)-")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Kurswert 509,71- EUR
                // Kundenbonifikation 40 % vom Ausgabeaufschlag 9,71 EUR
                // Ausgabeaufschlag pro Anteil 5,00 %
                .section("feeFx", "feeFy", "amountFx", "currency").optional()
                .match("^Kurswert (?<amountFx>[\\.,\\d]+)\\- (?<currency>[\\w]{3})")
                .match("^Kundenbonifikation (?<feeFy>[\\.,\\d]+) % vom Ausgabeaufschlag [\\.,\\d]+ [\\w]{3}")
                .match("^Ausgabeaufschlag pro Anteil (?<feeFx>[\\.,\\d]+) %")
                .assign((t, v) -> {
                    // Fee in percent
                    double amountFx = Double.parseDouble(v.get("amountFx").replace(',', '.'));
                    double feeFy = Double.parseDouble(v.get("feeFy").replace(',', '.'));
                    double feeFx = Double.parseDouble(v.get("feeFx").replace(',', '.'));
                    feeFy = (amountFx / (1 + feeFx / 100)) * (feeFx / 100) * (feeFy / 100);
                    String fee =  Double.toString((amountFx / (1 + feeFx / 100)) * (feeFx / 100) - feeFy).replace('.', ',');
                    v.put("fee", fee);

                    processFeeEntries(t, v, type);
                })

                // Kurswert
                // EUR 14,40-
                .section("fee", "currency").optional()
                .match("^Kurswert$")
                .match("^(?<currency>[\\w]{3}) (?<fee>[\\.,\\d]+)\\-$")
                .assign((t, v) -> processFeeEntries(t, v, type));
    }

    private void processTaxEntries(Object t, Map<String, String> v, DocumentType type)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")));
            PDFExtractorUtils.checkAndSetTax(tax, (name.abuchen.portfolio.model.Transaction) t, type);
        }
        else
        {
            Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")));
            PDFExtractorUtils.checkAndSetTax(tax, ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction(), type);
        }
    }

    private void processFeeEntries(Object t, Map<String, String> v, DocumentType type)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            Money fee = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("fee")));
            PDFExtractorUtils.checkAndSetFee(fee, 
                            (name.abuchen.portfolio.model.Transaction) t, type);
        }
        else
        {
            Money fee = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("fee")));
            PDFExtractorUtils.checkAndSetFee(fee,
                            ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction(), type);
        }
    }

    private void addTax(Object t, Map<String, String> v, DocumentType type, String taxtype)
    {
        // Wenn es 'Einbehaltene Quellensteuer' gibt, dann die weiteren
        // Quellensteuer-Arten nicht berücksichtigen.
        if (checkWithholdingTax(type, taxtype))
        {
            name.abuchen.portfolio.model.Transaction tx = getTransaction(t);

            String currency = asCurrencyCode(v.get("currency"));
            long amount = asAmount(v.get(taxtype));

            if (!currency.equals(tx.getCurrencyCode()) && type.getCurrentContext().containsKey(EXCHANGE_RATE))
            {
                BigDecimal rate = BigDecimal.ONE.divide(
                                asExchangeRate(type.getCurrentContext().get(EXCHANGE_RATE)), 10,
                                RoundingMode.HALF_DOWN);

                currency = tx.getCurrencyCode();
                amount = rate.multiply(BigDecimal.valueOf(amount)).setScale(0, RoundingMode.HALF_DOWN).longValue();
            }

            tx.addUnit(new Unit(Unit.Type.TAX, Money.of(currency, amount)));
        }
    }

    private boolean checkWithholdingTax(DocumentType type, String taxtype)
    {
        if (Boolean.valueOf(type.getCurrentContext().get(FLAG_WITHHOLDING_TAX_FOUND)))
        {
            if ("quellenstanr".equalsIgnoreCase(taxtype))
                return false;
        }
        return true;
    }

    private name.abuchen.portfolio.model.Transaction getTransaction(Object t)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            return ((name.abuchen.portfolio.model.Transaction) t);
        }
        else
        {
            return ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction();
        }
    }
}
