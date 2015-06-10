/* Copyright (c) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.trivialdrivesample;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.android.trivialdrivesample.util.IabHelper;
import com.example.android.trivialdrivesample.util.IabResult;
import com.example.android.trivialdrivesample.util.Inventory;
import com.example.android.trivialdrivesample.util.Purchase;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.purchase.InAppPurchaseResult;
import com.google.android.gms.ads.purchase.PlayStorePurchaseListener;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Logger;
import com.google.android.gms.analytics.Tracker;
import com.googlekorea.test.trivialdrive.R;

public class MainActivity extends Activity {
    // Debug tag, for logging
    static final String TAG = "TrivialDrive";

    // TODO: Your Google Play console App License Key is here
    private static final String YOUR_LICENSE_KEY = "YOUR_LICENSE_KEY";

    // TODO: Your Ad Unit ID is here
    private static final String YOUR_AD_UNIT_ID = "YOUR_AD_UNIT_ID";

    // TODO: Your Property Id is here
    private static final String YOUR_PROPERTY_ID = "YOUR_PROPERTY_ID";

    // SKUs for our products: gas (consumable)
    private static final String SKU_GAS = "gas";
    // (arbitrary) request code for the purchase flow
    private static final int RC_REQUEST = 10001;
    // Graphics for the gas gauge
    private final static int[] TANK_RES_IDS = { R.drawable.gas0, R.drawable.gas1, R.drawable.gas2,
                                   R.drawable.gas3, R.drawable.gas4 };
    // How many units (1/4 tank is our unit) fill in the tank.
    private final static int TANK_MAX = 4;

    // Pre-defined GA Event category constant strings
    private final static String GA_CATEGORY_PURCHASE = "CATEGORY_PURCHASE";
    private final static String GA_CATEGORY_GAME_PROGRESS = "CATEGORY_GAME_PROGRESS";

    // Pre-defined GA Event action constant strings
    private final static String GA_ACTION_PURCHASE_GAS = "ACTION_PURCHASE_GAS";
    private final static String GA_ACTION_STAGE_CLEAER = "ACTION_STAGE_CLEAR";

    // Current amount of gas in tank, in units
    private int mTank;

    // Total trip distance in meter.
    private int mTripDistance;

    // Current Stage
    private int mCurrentStage;

    // IAB helper object
    private IabHelper mHelper;

    // AdMob InterstitialAd
    private InterstitialAd mInterstitial;

    // GA AppTracker
    private Tracker mAppTracker;

    // flag
    private boolean mShowAd = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initIAPHelper();
        initInterstitialAd();
        initGATracker();

        // load game data
        loadData();
    }

    // We're being destroyed. It's important to dispose of the helper here!
    @Override
    public void onDestroy() {
        super.onDestroy();

        // very important:
        Log.d(TAG, "Destroying helper.");
        if (mHelper != null) {
            mHelper.dispose();
            mHelper = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult(" + requestCode + "," + resultCode + "," + data);
        if (mHelper == null) return;

        // Pass on the activity result to the helper for handling
        if (!mHelper.handleActivityResult(requestCode, resultCode, data)) {
            // not handled, so handle it ourselves (here's where you'd
            // perform any handling of activity results not related to in-app
            // billing...
            super.onActivityResult(requestCode, resultCode, data);
        }
        else {
            Log.d(TAG, "onActivityResult handled by IABUtil.");
        }
    }

    // Drive button clicked. Burn gas!
    public void onDriveButtonClicked(View arg0) {
        Log.d(TAG, "Drive button clicked.");
        if (mTank <= 0) alert("Oh, no! You are out of gas! Try buying some!");
        else {
            --mTank;
            mTripDistance += 100;
            int stage = (mTripDistance / 301) + 1;

            if (mCurrentStage < stage) {
                mCurrentStage = stage;
                //cleared stage info is changed. Send GA event.
                mAppTracker.send(new HitBuilders.EventBuilder(
                        GA_CATEGORY_GAME_PROGRESS, GA_ACTION_STAGE_CLEAER)
                        .setLabel(String.valueOf(stage)).build());
                loadInterstitial();
            }

            saveData();
            alert("Vroooom, you drove 100m.");
            updateUi();
            Log.d(TAG, "Vrooom. Tank is now " + mTank);

            if (mShowAd) {
                showInterstitial();
            }
        }
    }

    // User clicked the "Buy Gas" button
    public void onBuyGasButtonClicked(View arg0) {
        Log.d(TAG, "Buy gas button clicked.");

        if (mTank >= TANK_MAX) {
            complain("Your tank is full. Drive around a bit!");
            return;
        }

        // launch the gas purchase UI flow.
        // We will be notified of completion via mPurchaseFinishedListener
        setWaitScreen(true);
        Log.d(TAG, "Launching purchase flow for gas.");

        /* TODO: for security, generate your payload here for verification. See the comments on
         *        verifyDeveloperPayload() for more info. Since this is a SAMPLE, we just use
         *        an empty string, but on a production app you should carefully generate this. */
        String payload = "";

        mHelper.launchPurchaseFlow(this, SKU_GAS, RC_REQUEST,
                mPurchaseFinishedListener, payload);
    }

    // updates UI to reflect model
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void updateUi() {
        // update the car color to reflect premium status or lack thereof

        // update gas gauge to reflect tank status
        int index = mTank >= TANK_RES_IDS.length ? TANK_RES_IDS.length - 1 : mTank;
        ((ImageView)findViewById(R.id.gas_gauge)).setImageResource(TANK_RES_IDS[index]);
        ((TextView)findViewById(R.id.trip_distance)).setText("DISTANCE: " + mTripDistance + "m");
        ((TextView) findViewById(R.id.stage)).setText("Stage " + mCurrentStage);

        ((ImageView)findViewById(R.id.free_or_premium)).getDrawable().setTint(
                Color.argb(255, 50 * mCurrentStage % 250, 120, 120));
    }

    // Enables or disables the "please wait" screen.
    private void setWaitScreen(boolean set) {
        findViewById(R.id.screen_main).setVisibility(set ? View.GONE : View.VISIBLE);
        findViewById(R.id.screen_wait).setVisibility(set ? View.VISIBLE : View.GONE);
    }

    private void complain(String message) {
        Log.e(TAG, "**** TrivialDrive Error: " + message);
        alert("Error: " + message);
    }

    private void alert(String message) {
        AlertDialog.Builder bld = new AlertDialog.Builder(this);
        bld.setMessage(message);
        bld.setNeutralButton("OK", null);
        Log.d(TAG, "Showing alert dialog: " + message);
        bld.create().show();
    }

    private void saveData() {
        SharedPreferences.Editor spe = getPreferences(MODE_PRIVATE).edit();
        spe.putInt("tank", mTank);
        spe.putInt("trip_distance", mTripDistance);
        spe.putBoolean("show_ad", mShowAd);
        spe.commit();
        Log.d(TAG, "Saved data: tank = " + String.valueOf(mTank));
    }

    private void loadData() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        mTank = TANK_MAX;   //to make it easy to test features.
        mTripDistance = sp.getInt("trip_distance", 0);
        mCurrentStage = mTripDistance / 301 + 1;
        mShowAd = sp.getBoolean("show_ad", true);
        Log.d(TAG, "Loaded data: tank = " + String.valueOf(mTank));
        Log.d(TAG, "Loaded data: trip distance= " + String.valueOf(mTripDistance));

        if (mShowAd) {
            loadInterstitial();
        }
    }

    private void initIAPHelper() {
        // Create the helper, passing it our context and the public key to verify signatures with
        Log.d(TAG, "Creating IAB helper.");
        mHelper = new IabHelper(this, YOUR_LICENSE_KEY);
        // enable debug logging (for a production application, you should set this to false).
        mHelper.enableDebugLogging(true);

        // Start setup. This is asynchronous and the specified listener
        // will be called once setup completes.
        Log.d(TAG, "Starting setup.");
        mHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
            public void onIabSetupFinished(IabResult result) {
                Log.d(TAG, "Setup finished.");

                if (!result.isSuccess()) {
                    // Oh noes, there was a problem.
                    complain("Problem setting up in-app billing: " + result);
                    return;
                }

                // Have we been disposed of in the meantime? If so, quit.
                if (mHelper == null) return;

                // IAB is fully set up. Now, let's get an inventory of stuff we own.
                Log.d(TAG, "Setup successful. Querying inventory.");
                mHelper.queryInventoryAsync(mGotInventoryListener);
            }
        });
    }

    private void initGATracker() {
        // TODO: Create tracker with your prpoerty Id.
        // Enabling AutoActivtiy Tracking and AdvertisingIdCollection
    }

    private void initInterstitialAd() {
        // TODO: Create InterstitialAd instance, set PlayStorePurchase Listener and Ad Unit Id.
    }

    private void showInterstitial() {
        // TODO: If ad is loaded, show it

        mShowAd = false;
    }

    private void loadInterstitial() {
        mShowAd = true;

        // TODO: create a request and load ad using it.
    }

    // Callback for when a IAP ad is finished
    private PlayStorePurchaseListener mPlayStorePurchasedListener = new PlayStorePurchaseListener() {

        @Override
        public boolean isValidPurchase(String sku) {
            // TODO: check if the product has already been purchased.
            return true;
        }

        @Override
        public void onInAppPurchaseFinished(InAppPurchaseResult result) {
            // TODO: your custom process goes here, e.g., add coins after purchase.

            // result.finishPurchase();
        }
    };

    // Callback for when a purchase is finished
    private IabHelper.OnIabPurchaseFinishedListener mPurchaseFinishedListener = new IabHelper.OnIabPurchaseFinishedListener() {
        public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
            Log.d(TAG, "Purchase finished: " + result + ", purchase: " + purchase);

            // if we were disposed of in the meantime, quit.
            if (mHelper == null) return;

            if (result.isFailure()) {
                complain("Error purchasing: " + result);
                setWaitScreen(false);
                return;
            }

            Log.d(TAG, "Purchase successful.");
            if (purchase.getSku().equals(SKU_GAS)) {
                // bought 1/4 tank of gas. So consume it.
                Log.d(TAG, "Purchase is gas. Starting gas consumption.");
                mHelper.consumeAsync(purchase, mConsumeFinishedListener);

                mAppTracker.send(new HitBuilders.EventBuilder(
                        GA_CATEGORY_PURCHASE
                        , GA_ACTION_PURCHASE_GAS).build());
            }
        }
    };

    // Called when consumption is complete
    private IabHelper.OnConsumeFinishedListener mConsumeFinishedListener = new IabHelper.OnConsumeFinishedListener() {
        public void onConsumeFinished(Purchase purchase, IabResult result) {
            Log.d(TAG, "Consumption finished. Purchase: " + purchase + ", result: " + result);

            // if we were disposed of in the meantime, quit.
            if (mHelper == null) return;

            // We know this is the "gas" sku because it's the only one we consume,
            // so we don't check which sku was consumed. If you have more than one
            // sku, you probably should check...
            if (result.isSuccess()) {
                // successfully consumed, so we apply the effects of the item in our
                // game world's logic, which in our case means filling the gas tank a bit
                Log.d(TAG, "Consumption successful. Provisioning.");
                mTank = TANK_MAX;
                saveData();
                alert("You filled the tank.");
            }
            else {
                complain("Error while consuming: " + result);
            }
            updateUi();
            setWaitScreen(false);
            Log.d(TAG, "End consumption flow.");
        }
    };

    // Listener that's called when we finish querying the items and subscriptions we own
    private IabHelper.QueryInventoryFinishedListener mGotInventoryListener = new IabHelper.QueryInventoryFinishedListener() {
        public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
            Log.d(TAG, "Query inventory finished.");

            // Have we been disposed of in the meantime? If so, quit.
            if (mHelper == null) return;

            // Is it a failure?
            if (result.isFailure()) {
                complain("Failed to query inventory: " + result);
                return;
            }

            Log.d(TAG, "Query inventory was successful.");

            /*
             * Check for items we own. Notice that for each purchase, we check
             * the developer payload to see if it's correct! See
             * verifyDeveloperPayload().
             */

            // Check for gas delivery -- if we own gas, we should fill up the tank immediately
            Purchase gasPurchase = inventory.getPurchase(SKU_GAS);
            if (gasPurchase != null) {
                Log.d(TAG, "We have gas. Consuming it.");
                mHelper.consumeAsync(inventory.getPurchase(SKU_GAS), mConsumeFinishedListener);
                return;
            }

            updateUi();
            setWaitScreen(false);
            Log.d(TAG, "Initial inventory query finished; enabling main UI.");
        }
    };

}