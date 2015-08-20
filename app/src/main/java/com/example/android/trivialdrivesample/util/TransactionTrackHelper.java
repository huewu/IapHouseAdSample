package com.example.android.trivialdrivesample.util;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.analytics.ecommerce.Product;
import com.google.android.gms.analytics.ecommerce.ProductAction;

/**
 * Created by chansuk on 15. 8. 20..
 */
public class TransactionTrackHelper {

    public static void sendProductImpressionPing(Tracker tracker,  String sku, String name) {
        Product product = new Product()
                .setId(sku)
                .setName(name);
        HitBuilders.ScreenViewBuilder builder = new HitBuilders.ScreenViewBuilder()
                .addImpression(product, "Showing IAP Ad");
        tracker.send(builder.build());
    }

    public static void sendProductClickedPing(Tracker tracker,  String sku, String name) {
        Product product = new Product()
                .setId(sku)
                .setName(name);
        ProductAction productAction = new ProductAction(ProductAction.ACTION_CLICK);
        HitBuilders.ScreenViewBuilder builder = new HitBuilders.ScreenViewBuilder()
                .addProduct(product)
                .setProductAction(productAction);

        tracker.send(builder.build());
    }


    public static void sendProductPurchasedPing(Tracker tracker,  String sku, String name) {
        Product product = new Product()
                .setId(sku)
                .setName(name);
        ProductAction productAction = new ProductAction(ProductAction.ACTION_PURCHASE);
        HitBuilders.ScreenViewBuilder builder = new HitBuilders.ScreenViewBuilder()
                .addProduct(product)
                .setProductAction(productAction);

        tracker.send(builder.build());
    }


}
