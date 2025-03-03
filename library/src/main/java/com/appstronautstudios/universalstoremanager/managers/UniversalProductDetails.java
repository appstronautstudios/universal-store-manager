package com.appstronautstudios.universalstoremanager.managers;

import com.android.billingclient.api.ProductDetails;

import java.util.List;

public class UniversalProductDetails {
    private final String productId;
    private final String title;
    private final String description;
    private final String priceText; // Formatted price string (e.g., "$4.99")
    private final String priceCurrencyCode;
    private final float priceValue; // Direct float representation of the price
    private final boolean isSubscription;

    public UniversalProductDetails(String productId, String title, String description, String priceText, String priceCurrencyCode, float priceValue, boolean isSubscription) {
        this.productId = productId;
        this.title = title;
        this.description = description;
        this.priceText = priceText;
        this.priceCurrencyCode = priceCurrencyCode;
        this.priceValue = priceValue;
        this.isSubscription = isSubscription;
    }

    /**
     * @param details - Google Billing ProductDetails object to parse and expose
     * @return - UniversalProductDetails object with title, price float, currency code etc
     * @throws Exception - If there are any issues parsing out this detail object throw an exception with a string error message
     */
    public static UniversalProductDetails fromProductDetails(ProductDetails details) throws Exception {
        if (details == null) {
            throw new Exception("Null product details");
        }

        boolean isSubscription = details.getSubscriptionOfferDetails() != null;
        float priceAmount = 0.0f;
        String formattedPrice = "0.00";
        String currencyCode = "N/A";

        if (isSubscription) {
            List<ProductDetails.SubscriptionOfferDetails> offerDetails = details.getSubscriptionOfferDetails();
            if (offerDetails != null && !offerDetails.isEmpty()) {
                // determine the correct pricing phase to reference for pricing
                ProductDetails.PricingPhase pricingPhase = null;
                for (ProductDetails.SubscriptionOfferDetails offer : offerDetails) {
                    List<ProductDetails.PricingPhase> pricingPhases = offer.getPricingPhases().getPricingPhaseList();
                    if (pricingPhases != null && !pricingPhases.isEmpty()) {
                        for (ProductDetails.PricingPhase phase : pricingPhases) {
                            // make sure we ignore free trial phase otherwise price string will show
                            // up as "FREE" for users that haven't used a trial yet
                            if (phase.getPriceAmountMicros() > 0) {
                                pricingPhase = phase;
                                break; // found a valid phase - break out
                            }
                        }
                    }
                    if (pricingPhase != null) {
                        break; // found a valid phase - break out
                    }
                }
                if (pricingPhase == null) {
                    // safety check if we can't resolve a valid pricing phase
                    throw new Exception("No pricing phase found");
                }
                formattedPrice = pricingPhase.getFormattedPrice();
                currencyCode = pricingPhase.getPriceCurrencyCode();
                priceAmount = pricingPhase.getPriceAmountMicros() / 1_000_000f; // Convert micros to float
            } else {
                throw new Exception("Null subscription offer details");
            }
        } else {
            ProductDetails.OneTimePurchaseOfferDetails oneTimePurchaseDetails = details.getOneTimePurchaseOfferDetails();
            if (oneTimePurchaseDetails != null) {
                formattedPrice = oneTimePurchaseDetails.getFormattedPrice();
                currencyCode = oneTimePurchaseDetails.getPriceCurrencyCode();
                priceAmount = oneTimePurchaseDetails.getPriceAmountMicros() / 1_000_000f; // Convert micros to float
            } else {
                throw new Exception("Null one-time purchase offer details");
            }
        }

        return new UniversalProductDetails(
                details.getProductId(),
                details.getTitle(),
                details.getDescription(),
                formattedPrice,
                currencyCode,
                priceAmount,
                isSubscription
        );
    }

    // Getters
    public String getProductId() {
        return productId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getPriceText() {
        return priceText;
    }

    public String getPriceCurrencyCode() {
        return priceCurrencyCode;
    }

    public float getPriceValue() {
        return priceValue;
    }

    public boolean isSubscription() {
        return isSubscription;
    }
}
