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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.android.trivialdrivesample.util.IabHelper;
import com.example.android.trivialdrivesample.util.IabResult;
import com.example.android.trivialdrivesample.util.Inventory;
import com.example.android.trivialdrivesample.util.Purchase;
import com.google.ads.conversiontracking.AdWordsConversionReporter;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.purchase.InAppPurchase;
import com.google.android.gms.ads.purchase.InAppPurchaseListener;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;

import admob.demo.iap.defaultpurchase.R;

import static android.os.Build.VERSION;
import static android.os.Build.VERSION_CODES;

public class MainActivity extends Activity {
    // Debug tag, for logging
    static final String TAG = "TrivialDrive";

    // TODO: Your Google Play console App License Key should be here
    private static final String YOUR_LICENSE_KEY = "YOUR_LICENSE_KEY";

    // TODO: Your Ad Unit ID is here
    // For the testing purpose only, you can try 'ca-app-pub-2412876219430673/4320894148'
    private static final String YOUR_AD_UNIT_ID = "ca-app-pub-2412876219430673/4320894148";

    // TODO: Your Tracker Id is here
    private static final String YOUR_TRACKER_ID = "YOUR_TRACKER_ID";

    // SKUs for successful test purchase products
    private static final String SKU_TEST_SUCCEEDED = "android.test.purchased";
    // SKUs for unsuccessful test purchase products
    private static final String SKU_TEST_CANCELED= "android.test.canceled";

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
    private final static String GA_ACTION_STAGE_CLEAR = "ACTION_STAGE_CLEAR";

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

    // flag to indicate whether ad should be displayed or not.
    private boolean mShowAd = false;

    // flag to indicate whether the purchase was made from IAP Ad or not.
    private boolean mFromAd = false;
    private InAppPurchase mInAppPurchase = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initIAPHelper();
        initInterstitialAd();
        initGATracker();

        // load game data
        loadData();

        AdWordsConversionReporter.registerReferrer(this.getApplicationContext(),
                this.getIntent().getData());
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
        if (!mHelper.handleActivityResult(requestCode, resultCode, data, mFromAd)) {
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

            // stage number will be increased every successful two drives.
            int stage = (mTripDistance / 201) + 1;

            if (mCurrentStage < stage) {
                mCurrentStage = stage;

                // Send custom GA event.
                // Event Category: 'CATEGORY_GAME_PROGRESS'
                // Event Action: 'ACTION_STAGE_CLEAR'
                // Event Label: stage number in string
                mAppTracker.send(new HitBuilders.EventBuilder(
                        GA_CATEGORY_GAME_PROGRESS, GA_ACTION_STAGE_CLEAR)
                        .setLabel(String.valueOf(stage)).build());
            }

            saveData();
            alert("Vroooom, you drove 100m.");
            updateUi();
            Log.d(TAG, "Vrooom. Tank is now " + mTank);

            if (mTank < 2) {
                requestNewInterstitial();
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

        mFromAd = false;
        mHelper.launchPurchaseFlow(this, SKU_TEST_SUCCEEDED, RC_REQUEST,
                mPurchaseFinishedListener, payload);
    }

    // updates UI to reflect model
    private void updateUi() {
        // update the car color to reflect premium status or lack thereof

        // update gas gauge to reflect tank status
        int index = mTank >= TANK_RES_IDS.length ? TANK_RES_IDS.length - 1 : mTank;
        ((ImageView)findViewById(R.id.gas_gauge)).setImageResource(TANK_RES_IDS[index]);
        ((TextView)findViewById(R.id.trip_distance)).setText("DISTANCE: " + mTripDistance + "m");
        ((TextView) findViewById(R.id.stage)).setText("Stage " + mCurrentStage);

        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP ){
            // If this app is running on L,
            // let's change the color of car by using setTint method based on the stage number.
            ((ImageView)findViewById(R.id.free_or_premium)).getDrawable().setTint(
                    Color.argb(255, 50 * mCurrentStage % 250, 80, 80));
        }
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
        bld.setNeutralButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mShowAd) {
                    showInterstitial();
                }
            }
        });
        Log.d(TAG, "Showing alert dialog: " + message);
        bld.create().show();
    }

    private void saveData() {
        SharedPreferences.Editor spe = getPreferences(MODE_PRIVATE).edit();
        spe.putInt("tank", mTank);
        spe.putInt("trip_distance", mTripDistance);
        spe.putBoolean("show_ad", mShowAd);
        spe.apply();
        Log.d(TAG, "Saved data: tank = " + String.valueOf(mTank));
    }

    private void loadData() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        mTank = sp.getInt("tank", TANK_MAX);
        mTripDistance = sp.getInt("trip_distance", 0);
        mCurrentStage = mTripDistance / 301 + 1;
        mShowAd = sp.getBoolean("show_ad", true);
        Log.d(TAG, "Loaded data: tank = " + String.valueOf(mTank));
        Log.d(TAG, "Loaded data: trip distance= " + String.valueOf(mTripDistance));

        if (mShowAd) {
            requestNewInterstitial();
        }

        //AdWordsConversionReporter.
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
        // Create a tracker object with your prpoerty Id, and assign it to mAppTracker
        // Enabling AutoActivtiy Tracking and AdvertisingIdCollection
        mAppTracker = GoogleAnalytics.getInstance(this).newTracker(YOUR_TRACKER_ID);
        mAppTracker.enableAdvertisingIdCollection(true);
        mAppTracker.enableAutoActivityTracking(true);
        mAppTracker.enableExceptionReporting(true);
    }

    private void initInterstitialAd() {
        // TODO: create InterstitialAd instance here,
        // set mPlayStorePurchasedListener Listener and Ad Unit Id as well.
        mInterstitial = new InterstitialAd(this);
        mInterstitial.setAdUnitId(YOUR_AD_UNIT_ID);

        // null for parameter publicKey is acceptable
        // but SDK will work in developer mode and skip verifying purchase data signature
        // with public key.
        mInterstitial.setInAppPurchaseListener(mInAppPurchaseListener);
    }

    private void showInterstitial() {
        // TODO: Check whether the ad is loaded or not, and only if ad is loaded, show it.
        if (mInterstitial.isLoaded()) {
            mInterstitial.show();
            mShowAd = false;
            saveData();
        }
    }

    private void requestNewInterstitial() {
        // TODO: Create a default request and load ad using it.
        // To make sure you always request test ads, testing with live, production ads is
        // a violation of AdMob policy and can lead to suspension of your account.
        mShowAd = true;
        mInterstitial.loadAd(new AdRequest.Builder().build());
    }

    // Callback for when a purchase is finished via IAP house ad.
    private InAppPurchaseListener mInAppPurchaseListener = new InAppPurchaseListener() {

        @Override
        public void onInAppPurchaseRequested(InAppPurchase inAppPurchase) {
            /* TODO: for security, generate your payload here for verification. See the comments on
             *        verifyDeveloperPayload() for more info. Since this is a SAMPLE, we just use
             *        an empty string, but on a production app you should carefully generate this. */

            // launch the gas purchase UI flow.
            // We will be notified of completion via mPurchaseFinishedListener
            setWaitScreen(true);
            Log.d(TAG, "Launching purchase flow for gas from Ad.");

            final String payload = "";
            mFromAd = true;
            mInAppPurchase = inAppPurchase;

            // close interstitial ad.
            Intent intent = new Intent(MainActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);

            mHelper.launchPurchaseFlow(MainActivity.this, inAppPurchase.getProductId(), RC_REQUEST,
                    mPurchaseFinishedListener, payload);
        }
    };


    // Callback for when a purchase is finished
    private IabHelper.OnIabPurchaseFinishedListener mPurchaseFinishedListener
            = new IabHelper.OnIabPurchaseFinishedListener() {

        public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
            Log.d(TAG, "Purchase finished: " + result + ", purchase: " + purchase);

            // if we were disposed of in the meantime, quit.
            if (mHelper == null) return;

            if (mFromAd && mInAppPurchase != null) {
                // send conversion ping to AdMob
                mInAppPurchase.recordPlayBillingResolution(result.getResponse());
            }

            if (result.isFailure()) {
                complain("Error purchasing: " + result);
                setWaitScreen(false);
                return;
            }

            Log.d(TAG, "Purchase successful.");
            if (purchase.getSku().equals(SKU_TEST_SUCCEEDED)) {
                // bought 1/4 tank of gas. So consume it.
                Log.d(TAG, "Purchase is gas. Starting gas consumption.");
                mHelper.consumeAsync(purchase, mConsumeFinishedListener);
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
            Purchase gasPurchase = inventory.getPurchase(SKU_TEST_SUCCEEDED);
            if (gasPurchase != null) {
                Log.d(TAG, "We have gas. Consuming it.");
                mHelper.consumeAsync(inventory.getPurchase(SKU_TEST_SUCCEEDED), mConsumeFinishedListener);
                return;
            }

            updateUi();
            setWaitScreen(false);
            Log.d(TAG, "Initial inventory query finished; enabling main UI.");
        }
    };
}
