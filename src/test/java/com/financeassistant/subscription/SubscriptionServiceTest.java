package com.financeassistant.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import com.financeassistant.model.Subscription;
import com.financeassistant.model.Transaction;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class SubscriptionServiceTest {
    private DatabaseManager db;

    @Before
    public void setUp() {
        db = DatabaseManager.open(null);
    }

    private void charge(String description, double amount, LocalDate date) {
        db.save(new Transaction(date, -amount, description));
    }

    private void monthly(String description, double amount, int day, int fromMonth, int toMonth) {
        for (int m = fromMonth; m <= toMonth; m++) {
            charge(description, amount, LocalDate.of(2026, m, day));
        }
    }

    /** Fake Claude: names and types each numbered line by keyword. */
    private static FakeLLMClient labeller() {
        return new FakeLLMClient(prompt -> {
            if (!prompt.contains("Recurring charges")) {
                return "Keep one music service to save money.";
            }
            StringBuilder json = new StringBuilder("{");
            for (String line : prompt.split("\n")) {
                if (line.matches("\\d+\\. .*")) {
                    String n = line.substring(0, line.indexOf('.'));
                    String name = line.contains("SPOTIFY") ? "Spotify" : line.contains("APPLE") ? "Apple Music"
                            : line.contains("NETFLIX") ? "Netflix" : "Gym";
                    String type = name.equals("Netflix") ? "Video streaming" : name.equals("Gym") ? "Gym membership" : "music streaming";
                    json.append(json.length() > 1 ? "," : "").append('"').append(n).append("\":{\"name\":\"")
                            .append(name).append("\",\"type\":\"").append(type).append("\"}");
                }
            }
            return json.append('}').toString();
        });
    }

    @Test
    public void detectsMonthlyChargesWithPriceIncreaseAndNextDate() {
        charge("NETFLIX.COM", 16.99, LocalDate.of(2026, 6, 5));
        charge("NETFLIX.COM", 16.99, LocalDate.of(2026, 7, 6));
        charge("NETFLIX.COM", 18.99, LocalDate.of(2026, 8, 5));
        charge("NETFLIX.COM", 18.99, LocalDate.of(2026, 9, 4));
        List<Subscription> subs = new SubscriptionService(labeller()).detectSubscriptions().subscriptions();

        assertEquals(1, subs.size());
        Subscription netflix = subs.get(0);
        assertEquals("Netflix", netflix.getName());
        assertEquals("Video streaming", netflix.getServiceType());
        assertEquals(Subscription.Frequency.MONTHLY, netflix.getFrequency());
        assertTrue(netflix.isPriceIncreased());
        assertEquals(16.99, netflix.getPreviousAmount(), 0.001);
        assertEquals(18.99, netflix.getMonthlyCost(), 0.001);
        assertEquals(LocalDate.of(2026, 10, 4), netflix.getNextExpected());
        assertEquals(4, netflix.getOccurrences());
        assertTrue(netflix.isActive());
    }

    @Test
    public void irregularOrVaryingChargesAreNotSubscriptions() {
        monthly("SPOTIFY P2B4C1", 11.99, 8, 6, 9);           // a real subscription, keeps the history long enough
        charge("TIM HORTONS #2241", 4.35, LocalDate.of(2026, 6, 2));   // irregular dates
        charge("TIM HORTONS #2241", 4.35, LocalDate.of(2026, 6, 9));
        charge("TIM HORTONS #2241", 4.35, LocalDate.of(2026, 7, 30));
        charge("TORONTO HYDRO", 45.00, LocalDate.of(2026, 6, 18));     // regular dates, amounts jump around
        charge("TORONTO HYDRO", 79.00, LocalDate.of(2026, 7, 18));
        charge("TORONTO HYDRO", 52.00, LocalDate.of(2026, 8, 18));
        charge("GYM", 30.00, LocalDate.of(2026, 7, 1));               // only two charges
        charge("GYM", 30.00, LocalDate.of(2026, 8, 1));

        List<Subscription> subs = new SubscriptionService(labeller()).detectSubscriptions().subscriptions();
        assertEquals(1, subs.size());
        assertEquals("SPOTIFY", subs.get(0).getMerchantKey());
    }

    @Test
    public void weeklyChargesAreDetectedAndCostedPerMonth() {
        monthly("SPOTIFY", 11.99, 8, 6, 9);
        for (int i = 0; i < 8; i++) {
            charge("MEAL KIT CO", 50.00, LocalDate.of(2026, 7, 1).plusWeeks(i));
        }
        Subscription mealKit = new SubscriptionService(labeller()).detectSubscriptions().subscriptions().stream()
                .filter(s -> s.getMerchantKey().equals("MEAL KIT CO")).findFirst().orElseThrow();
        assertEquals(Subscription.Frequency.WEEKLY, mealKit.getFrequency());
        assertEquals(216.67, mealKit.getMonthlyCost(), 0.01);
    }

    @Test
    public void stoppedSubscriptionIsMarkedInactive() {
        monthly("SPOTIFY", 11.99, 8, 3, 9);
        monthly("OLD GYM", 30.00, 1, 3, 5); // last charged in May, data runs to September
        Subscription gym = new SubscriptionService(labeller()).detectSubscriptions().subscriptions().stream()
                .filter(s -> s.getMerchantKey().equals("OLD GYM")).findFirst().orElseThrow();
        assertFalse(gym.isActive());
    }

    @Test
    public void tooLittleHistoryExplainsWhy() {
        charge("SPOTIFY", 11.99, LocalDate.of(2026, 9, 8));
        charge("NETFLIX", 16.99, LocalDate.of(2026, 9, 20));
        SubscriptionScan scan = new SubscriptionService(labeller()).detectSubscriptions();
        assertTrue(scan.subscriptions().isEmpty());
        assertTrue(scan.message(), scan.message().contains("2-3 months"));

        DatabaseManager.open(null);
        assertTrue(new SubscriptionService(labeller()).detectSubscriptions().message().contains("Import a statement"));
    }

    @Test
    public void overlappingServicesAreFoundAndExplained() {
        monthly("SPOTIFY P2B4C1", 11.99, 8, 6, 9);
        monthly("APPLE.COM/BILL APPLE MUSIC", 10.99, 12, 6, 9);
        monthly("NETFLIX.COM", 16.99, 5, 6, 9);
        SubscriptionService service = new SubscriptionService(labeller());
        service.detectSubscriptions();

        Map<String, List<Subscription>> overlaps = service.findOverlaps();
        assertEquals(1, overlaps.size());
        assertEquals(2, overlaps.get("music streaming").size());
        assertEquals("Keep one music service to save money.", service.getOverlapMessage());
    }

    @Test
    public void overlapMessageFallsBackToTemplateWhenAiIsDown() {
        monthly("SPOTIFY P2B4C1", 11.99, 8, 6, 9);
        monthly("APPLE.COM/BILL APPLE MUSIC", 10.99, 12, 6, 9);
        new SubscriptionService(labeller()).detectSubscriptions(); // labels saved while AI was up

        SubscriptionService offline = new SubscriptionService(FakeLLMClient.failing("down"));
        String message = offline.getOverlapMessage();
        assertTrue(message, message.contains("save about $11.99 a month ($143.88 a year)"));
    }

    @Test
    public void aiDownStillDetectsButLeavesTypesEmpty() {
        monthly("SPOTIFY", 11.99, 8, 6, 9);
        SubscriptionScan scan = new SubscriptionService(FakeLLMClient.failing("timed out")).detectSubscriptions();
        assertEquals(1, scan.subscriptions().size());
        assertNull(scan.subscriptions().get(0).getServiceType());
        assertNotNull(scan.aiError());
    }

    @Test
    public void userChoicesSurviveARescan() {
        monthly("SPOTIFY", 11.99, 8, 6, 9);
        monthly("RENT PAYMENT - MAPLE", 1450, 1, 6, 9);
        SubscriptionService service = new SubscriptionService(labeller());
        service.detectSubscriptions();
        Subscription spotify = find(service, "SPOTIFY").orElseThrow();
        Subscription rent = find(service, "RENT PAYMENT").orElseThrow();

        service.rename(spotify.getId(), "My music");
        service.confirm(spotify.getId());
        service.markNotSubscription(rent.getId());
        charge("SPOTIFY", 11.99, LocalDate.of(2026, 10, 8));
        service.detectSubscriptions();

        Subscription again = find(service, "SPOTIFY").orElseThrow();
        assertEquals("My music", again.getName());
        assertEquals(Subscription.Status.CONFIRMED, again.getStatus());
        assertEquals(5, again.getOccurrences());
        assertTrue(find(service, "RENT PAYMENT").isEmpty()); // dismissed ones stay hidden
    }

    @Test
    public void detectedEntryThatNoLongerQualifiesIsRemoved() {
        monthly("SPOTIFY", 11.99, 8, 6, 9);
        monthly("NETFLIX", 16.99, 5, 6, 9);
        SubscriptionService service = new SubscriptionService(labeller());
        assertEquals(2, service.detectSubscriptions().subscriptions().size());
        charge("SPOTIFY", 99.99, LocalDate.of(2026, 10, 8)); // amounts now jump around
        List<Subscription> after = service.detectSubscriptions().subscriptions();
        assertEquals(1, after.size());
        assertEquals("NETFLIX", after.get(0).getMerchantKey());
    }

    @Test(expected = IllegalArgumentException.class)
    public void renameNeedsAName() {
        monthly("SPOTIFY", 11.99, 8, 6, 9);
        SubscriptionService service = new SubscriptionService(labeller());
        service.detectSubscriptions();
        service.rename(service.getSubscriptions().get(0).getId(), " ");
    }

    private static Optional<Subscription> find(SubscriptionService service, String key) {
        return service.getSubscriptions().stream().filter(s -> s.getMerchantKey().equals(key)).findFirst();
    }
}
