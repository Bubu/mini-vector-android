/*
 * Copyright 2015 OpenMarket Ltd
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

package im.vector.activity;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.call.IMXCall;
import org.matrix.androidsdk.call.MXCallsManager;
import im.vector.VectorApp;
import im.vector.Matrix;
import im.vector.R;
import im.vector.services.EventStreamService;
import im.vector.util.CallRingManager;
import im.vector.util.VectorUtils;
import im.vector.view.VectorPendingCallView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * VectorCallViewActivity is the call activity.
 */
public class VectorCallViewActivity extends Activity {
    private static final String LOG_TAG = "VCallViewActivity";

    public static final String EXTRA_MATRIX_ID = "CallViewActivity.EXTRA_MATRIX_ID";
    public static final String EXTRA_CALL_ID = "CallViewActivity.EXTRA_CALL_ID";
    public static final String EXTRA_AUTO_ACCEPT = "CallViewActivity.EXTRA_AUTO_ACCEPT";
    private static final String KEY_MIC_MUTE_STATUS = "KEY_MIC_MUTE_STATUS";
    private static final String KEY_SPEAKER_VIDEO_CALL_STATUS = "KEY_SPEAKER_VIDEO_CALL_STATUS";
    private static final String KEY_SPEAKER_AUDIO_CALL_STATUS = "KEY_SPEAKER_AUDIO_CALL_STATUS";

    private static VectorCallViewActivity instance = null;

    private static View mSavedCallview = null;
    private static IMXCall mCall = null;

    private View mCallView;

    // account info
    private String mMatrixId = null;
    private MXSession mSession = null;
    private String mCallId = null;

    // call info
    private boolean mAutoAccept = false;
    private boolean mIsCallEnded = false;

    // graphical items
    private ImageView mHangUpImageView;
    private ImageView mSpeakerSelectionView;
    private ImageView mAvatarView;
    private ImageView mMuteMicImageView;
    private ImageView mRoomLinkImageView;
    private VectorPendingCallView mHeaderPendingCallView;

    // video diplay size
    private IMXCall.VideoLayoutConfiguration mLocalVideoLayoutConfig;
    // hard coded values are taken from specs
    private static final float RATIO_TOP_MARGIN_LOCAL_USER_VIDEO = (float)(462.0/585.0);
    private static final float VIDEO_TO_BUTTONS_VERTICAL_SPACE = (float) (18.0/585.0);
    /**  local user video height is set as percent of the total screen height **/
    private static final int PERCENT_LOCAL_USER_VIDEO_SIZE = 25;
    private static final float RATIO_LOCAL_USER_VIDEO_HEIGHT = ((float)(PERCENT_LOCAL_USER_VIDEO_SIZE))/100;
    private static final float RATIO_LOCAL_USER_VIDEO_WIDTH = ((float)(PERCENT_LOCAL_USER_VIDEO_SIZE))/100;

    private final IMXCall.MXCallListener mListener = new IMXCall.MXCallListener() {
        @Override
        public void onStateDidChange(String state) {
            if (null != getInstance()) {
                final String fState = state;
                VectorCallViewActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(LOG_TAG, "## onStateDidChange(): new state=" + fState);
                        manageSubViews();
                    }
                });
            }
        }

        /**
         * Display the error messages
         * @param toast the message
         */
        private void showToast(final String toast)  {
            if (null != getInstance()) {
                getInstance().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (null != getInstance()) {
                            Toast.makeText(getInstance(), toast, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }

        @Override
        public void onCallError(String error) {
            Context context = getInstance();

            Log.d(LOG_TAG, "## onCallError(): error=" + error);

            if (null != context) {
                if (IMXCall.CALL_ERROR_USER_NOT_RESPONDING.equals(error)) {
                    showToast(context.getString(R.string.call_error_user_not_responding));
                } else if (IMXCall.CALL_ERROR_ICE_FAILED.equals(error)) {
                    showToast(context.getString(R.string.call_error_ice_failed));
                } else if (IMXCall.CALL_ERROR_CAMERA_INIT_FAILED.equals(error)) {
                    showToast(context.getString(R.string.call_error_camera_init_failed));
                }
            }
        }

        @Override
        public void onViewLoading(View callview) {
            Log.d(LOG_TAG, "## onViewLoading():");

            mCallView = callview;
            insertCallView();
        }

        @Override
        public void onViewReady() {
            // update UI before displaying the video
            computeVideoUiLayout();
            if (!mCall.isIncoming()) {
                Log.d(LOG_TAG, "## onViewReady(): placeCall()");
                mCall.placeCall(mLocalVideoLayoutConfig);
            } else {
                Log.d(LOG_TAG, "## onViewReady(): launchIncomingCall()");
                mCall.launchIncomingCall(mLocalVideoLayoutConfig);
            }
        }

        /**
         * The call was answered on another device
         */
        @Override
        public void onCallAnsweredElsewhere() {
            VectorCallViewActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d(LOG_TAG, "## onCallAnsweredElsewhere(): ");
                    clearCallData();
                    VectorCallViewActivity.this.finish();
                }
            });
        }

        @Override
        public void onCallEnd() {
            VectorCallViewActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d(LOG_TAG, "## onCallEnd(): ");

                    clearCallData();
                    mIsCallEnded = true;
                    VectorCallViewActivity.this.finish();
                }
            });
        }
    };

    /**
     * @return true if the call can be resumed.
     * i.e this callView can be closed to be re opened later.
     */
    private static boolean canCallBeResumed() {
        if (null != mCall) {
            String state = mCall.getCallState();

            // active call must be
            return
                    (state.equals(IMXCall.CALL_STATE_RINGING) && !mCall.isIncoming()) ||
                            state.equals(IMXCall.CALL_STATE_CONNECTING) ||
                            state.equals(IMXCall.CALL_STATE_CONNECTED) ||
                            state.equals(IMXCall.CALL_STATE_CREATE_ANSWER);
        }

        return false;
    }


    /**
     * @param callId the call Id
     * @return true if the call is the active callId
     */
    public static boolean isBackgroundedCallId(String callId) {
        boolean res = false;

        if ((null != mCall) && (null == instance)) {
            res = mCall.getCallId().equals(callId);
            // clear unexpected call.
            getActiveCall();
        }

        return res;
    }

    /**
     * Provides the active call.
     * The current call is tested to check if it is still valid.
     * It if it is no more valid, any call UIs are dismissed.
     * @return the active call
     */
    public static IMXCall getActiveCall() {
        // not currently displayed
        if ((instance == null) && (null != mCall)) {
            // check if the call can be resume
            // or it's still valid
            if (!canCallBeResumed() || (null == mCall.getSession().mCallsManager.callWithCallId(mCall.getCallId()))) {
                Log.d(LOG_TAG, "Hide the call notifications because the cuurent one cannot be resumed");
                EventStreamService.getInstance().hideCallNotifications();
                mCall = null;
                mSavedCallview = null;
            }
        }

        return mCall;
    }

    /**
     * @return the callViewActivity instance
     */
    private static VectorCallViewActivity getInstance() {
        return instance;
    }

    /**
     * release the call info
     */
    private void clearCallData() {
        if (null != mCall) {
            mCall.removeListener(mListener);
        }

        // remove header call view
        mHeaderPendingCallView.checkPendingCall();

        mCall = null;
        mCallView = null;
        mSavedCallview = null;
    }

    /**
     * Insert the callView in the activity (above the other room member).
     * The callView is setup in the SDK, and provided via onViewLoading() in {@link #mListener}.
     */
    private void insertCallView() {
        if(null != mCallView) {
            ImageView avatarView = (ImageView) VectorCallViewActivity.this.findViewById(R.id.call_other_member);

            // set the avatar
            VectorUtils.loadRoomAvatar(this, mSession, avatarView, mCall.getRoom());

            RelativeLayout layout = (RelativeLayout) VectorCallViewActivity.this.findViewById(R.id.call_layout);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
            params.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
            layout.removeView(mCallView);
            layout.addView(mCallView, 1, params);

            mCall.setVisibility(View.GONE);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(LOG_TAG,"## onCreate(): IN");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_callview);
        instance = this;

        final Intent intent = getIntent();
        if (intent == null) {
            Log.e(LOG_TAG, "Need an intent to view.");
            finish();
            return;
        }

        if (!intent.hasExtra(EXTRA_MATRIX_ID)) {
            Log.e(LOG_TAG, "No matrix ID extra.");
            finish();
            return;
        }

        mCallId = intent.getStringExtra(EXTRA_CALL_ID);
        mMatrixId = intent.getStringExtra(EXTRA_MATRIX_ID);

        mSession = Matrix.getInstance(getApplicationContext()).getSession(mMatrixId);
        if (null == mSession) {
            Log.e(LOG_TAG, "invalid session");
            finish();
            return;
        }

        mCall = mSession.mCallsManager.callWithCallId(mCallId);

        if (null == mCall) {
            Log.e(LOG_TAG, "invalid callId");
            finish();
            return;
        }

        // UI binding
        mHangUpImageView = (ImageView) findViewById(R.id.hang_up_button);
        mSpeakerSelectionView = (ImageView) findViewById(R.id.call_speaker_view);
        mAvatarView = (ImageView)VectorCallViewActivity.this.findViewById(R.id.call_other_member);
        mMuteMicImageView = (ImageView)VectorCallViewActivity.this.findViewById(R.id.mute_audio);
        mRoomLinkImageView = (ImageView)VectorCallViewActivity.this.findViewById(R.id.room_chat_link);
        mHeaderPendingCallView = (VectorPendingCallView) findViewById(R.id.header_pending_callview);

        mRoomLinkImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRoomActivity();
            }
        });

        mMuteMicImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMicMute();
                refreshMuteMicButton();
            }
        });

        mHangUpImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onHangUp();
            }
        });

        mSpeakerSelectionView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null != mCall) {
                    toggleSpeaker();
                    refreshSpeakerButton();
                }
            }
        });

        mAutoAccept = intent.hasExtra(EXTRA_AUTO_ACCEPT);

        CallRingManager.initMediaPlayerVolume();

        // init the call button
        restoreUserUiAudioSettings();
        manageSubViews();

        // the webview has been saved after a screen rotation
        // getParent() != null : the static value have been reused whereas it should not
        if ((null != mSavedCallview) && (null == mSavedCallview.getParent())) {
            mCallView = mSavedCallview;
            insertCallView();
        } else {
            Log.d(LOG_TAG, "onCreate: Hide the call notifications");
            EventStreamService.getInstance().hideCallNotifications();
            mSavedCallview = null;

            // create the callview asap
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mCall.createCallView();
                }
            });
        }

        setupHeaderPendingCallView();
        Log.d(LOG_TAG,"## onCreate(): OUT");
    }

    /**
     * Customize the header pending call view to match the video/audio call UI.
     */
    private void setupHeaderPendingCallView(){
        if(null != mHeaderPendingCallView) {
            // set the gradient effect in the background
            View mainContainerView = mHeaderPendingCallView.findViewById(R.id.main_view);
            mainContainerView.setBackgroundResource(R.drawable.call_header_transparent_bg);

            // remove the call icon and display the back arrow icon
            mHeaderPendingCallView.findViewById(R.id.call_icon_container).setVisibility(View.GONE);
            View backButtonView = mHeaderPendingCallView.findViewById(R.id.back_icon);
            backButtonView.setVisibility(View.VISIBLE);
            backButtonView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // simulate a back button press
                    if (!canCallBeResumed()) {
                        if (null != mCall) {
                            mCall.hangup("");
                        }
                    } else {
                        saveCallView();
                    }
                    VectorCallViewActivity.this.onBackPressed();
                }
            });

            // center the text horizontally and remove any padding
            LinearLayout textInfoContainerView = (LinearLayout)mHeaderPendingCallView.findViewById(R.id.call_info_container);
            textInfoContainerView.setHorizontalGravity(Gravity.CENTER_HORIZONTAL);
            textInfoContainerView.setPadding(0,0,0,0);

            // prevent the status call to be displayed
            mHeaderPendingCallView.enableCallStatusDisplay(false);
        }
    }

    /**
     * Toggle the mute feature of the mic.
     * <br>Corresponding value is saved in the shared preference.
     */
    private void toggleMicMute() {
        AudioManager audioManager = (AudioManager) VectorCallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
        if(null != audioManager) {
            boolean isMuted = audioManager.isMicrophoneMute();
            Log.d(LOG_TAG,"## toggleMicMute(): current mute val="+isMuted+" new mute val="+!isMuted);
            audioManager.setMicrophoneMute(!isMuted);

            // save user choice in shared preference
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(KEY_MIC_MUTE_STATUS, audioManager.isMicrophoneMute());
            editor.apply();
        } else {
            Log.w(LOG_TAG,"## toggleMicMute(): Failed due to invalid AudioManager");
        }
    }

    /**
     * Toggle the mute feature of the mic.
     * <br>Corresponding value is saved in the shared preference.
     */
    private void toggleSpeaker() {
        if(null != mCall) {
            mCall.toggleSpeaker();

            // save user choice in shared preference
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (null != audioManager) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor editor = preferences.edit();
                String keyPref = mCall.isVideo()?KEY_SPEAKER_VIDEO_CALL_STATUS:KEY_SPEAKER_AUDIO_CALL_STATUS;

                editor.putBoolean(keyPref, audioManager.isSpeakerphoneOn());
                editor.apply();
            } else {
                Log.w(LOG_TAG, "## toggleSpeaker(): Failed due to invalid AudioManager");
            }
        } else {
            Log.w(LOG_TAG, "## toggleSpeaker(): Failed");
        }
    }

    /**
     * Update the speaker and the mic mute status according to the values
     * saved by the user in the shared preference.
     * <br>See {@link #toggleSpeaker()} and {@link #toggleMicMute()}.
     */
    private void restoreUserUiAudioSettings() {
        boolean isSpeakerOn = false;
        boolean isMicMute;
        AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        if(null != preferences) {
            isMicMute = preferences.getBoolean(KEY_MIC_MUTE_STATUS, false/* default = un mute  */);

            if (null != mCall) {
                if (mCall.isVideo()) {
                    // for video calls, default value speaker = enabled
                    isSpeakerOn = preferences.getBoolean(KEY_SPEAKER_VIDEO_CALL_STATUS, true);
                } else {
                    // for audio calls, default value speaker = disabled
                    isSpeakerOn = preferences.getBoolean(KEY_SPEAKER_AUDIO_CALL_STATUS, false);
                }
            }

            // apply mute & speaker values
            if (null != audioManager) {
                audioManager.setMicrophoneMute(isMicMute);
                audioManager.setSpeakerphoneOn(isSpeakerOn);
            } else {
                Log.w(LOG_TAG, "## restoreUserUiAudioSettings(): Failed due to invalid AudioManager");
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        CommonActivityUtils.onLowMemory(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        CommonActivityUtils.onTrimMemory(this, level);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // assume that the user cancels the call if it is ringing
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!canCallBeResumed()) {
                if (null != mCall) {
                    mCall.hangup("");
                }
            } else {
                saveCallView();
            }
        } else if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) || (keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            // this is a trick to reduce the ring volume :
            // when the call is ringing, the AudioManager.Mode switch to MODE_IN_COMMUNICATION
            // so the volume is the next call one whereas the user expects to reduce the ring volume.
            if ((null != mCall) && mCall.getCallState().equals(IMXCall.CALL_STATE_RINGING)) {
                AudioManager audioManager = (AudioManager) VectorCallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
                // IMXChrome call issue
                if (audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                    int musicVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) * audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0);
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void finish() {
        super.finish();
        CallRingManager.stopRinging(this);
        instance = null;
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (null != mCall) {
            mCall.onPause();
            mCall.removeListener(mListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        mHeaderPendingCallView.checkPendingCall();

        // compute video UI layout position after rotation
        computeVideoUiLayout();
        // apply new position
        if ((null != mCall) && mCall.isVideo() && mCall.getCallState().equals(IMXCall.CALL_STATE_CONNECTED)) {
            mCall.updateLocalVideoRendererPosition(mLocalVideoLayoutConfig);
        }

        if (null != mCall) {
            mCall.onResume();

            mCall.addListener(mListener);

            final String fState = mCall.getCallState();

            Log.d(LOG_TAG, "## onResume(): call state=" + fState);

            // restore video layout after rotation
            mCallView = mSavedCallview;
            insertCallView();

            // init the call button
            manageSubViews();
        } else {
            this.finish();
        }
    }

    /**
     * Compute the top margin of the view that contains the video
     * of the local attendee of the call (the small video, where
     * the user sees himself).<br>
     * Ratios are taken from the UI specifications. The vertical space
     * between the video view and the container (call_menu_buttons_layout_container)
     * containing the buttons of the video menu, is specified as 4.3% of
     * the height screen.
     */
    private void computeVideoUiLayout() {
        String msgDebug="## computeVideoUiLayout():";

        mLocalVideoLayoutConfig = new IMXCall.VideoLayoutConfiguration();
        mLocalVideoLayoutConfig.mWidth = PERCENT_LOCAL_USER_VIDEO_SIZE;
        mLocalVideoLayoutConfig.mHeight = PERCENT_LOCAL_USER_VIDEO_SIZE;

        // get screen orientation:
        int screenOrientation = getResources().getConfiguration().orientation;

        // get the height of the screen
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        float screenHeight = (float)(metrics.heightPixels);
        float screenWidth = (float)(metrics.widthPixels);

        // compute action bar size: the video Y component starts below the action bar
        int actionBarHeight=0;
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
            screenHeight -= actionBarHeight;
        }

        View mMenuButtonsContainerView = VectorCallViewActivity.this.findViewById(R.id.hang_up_button);
        ViewGroup.LayoutParams layout = mMenuButtonsContainerView.getLayoutParams();
        float buttonsContainerHeight = (float)(layout.height);

        // base formula:
        // screenHeight = actionBarHeight + topMarginHeightLocalUserVideo + localVideoHeight + "height between video bottom & buttons" + buttonsContainerHeight
        //float topMarginHeightNormalized = 1 - RATIO_LOCAL_USER_VIDEO_HEIGHT - VIDEO_TO_BUTTONS_VERTICAL_SPACE;

        float topMarginHeightNormalized = 0; // range [0;1]
        float ratioVideoHeightNormalized = 0; // range [0;1]
        float localVideoWidth = Math.min(screenHeight,screenWidth/*portrait is ref*/)*RATIO_LOCAL_USER_VIDEO_HEIGHT; // value effectively applied by the SDK
        float estimatedLocalVideoHeight = (float) ((localVideoWidth)/(0.65)); // 0.65 => to adapt

        if(false /*Configuration.ORIENTATION_LANDSCAPE == screenOrientation*/){
            Log.d(LOG_TAG,"## computeVideoUiLayout(): orientation = LANDSCAPE");

            // landscape: video displayed in the left side, centered vertically
            mLocalVideoLayoutConfig.mX = 0;

            // for landscape, the video width is used in the Y axis
            ratioVideoHeightNormalized = (localVideoWidth/screenHeight);
            topMarginHeightNormalized = 1 - ratioVideoHeightNormalized - (buttonsContainerHeight/screenHeight);
            topMarginHeightNormalized /=2; // centered vertically => equal space before and after the video
        } else {
            if(Configuration.ORIENTATION_LANDSCAPE == screenOrientation){
                // take the video width as height
                ratioVideoHeightNormalized = (localVideoWidth/screenHeight);
            } else {
                mLocalVideoLayoutConfig.mIsPortrait = true;
                // take the video height as height
                ratioVideoHeightNormalized = estimatedLocalVideoHeight/screenHeight;
            }
            Log.d(LOG_TAG,"## computeVideoUiLayout(): orientation = PORTRAIT");

            // portrait: video displayed above the video buttons, centered horizontally
            mLocalVideoLayoutConfig.mX = (100 - PERCENT_LOCAL_USER_VIDEO_SIZE) / 2;
            topMarginHeightNormalized = 1 - ratioVideoHeightNormalized - VIDEO_TO_BUTTONS_VERTICAL_SPACE - (buttonsContainerHeight/screenHeight);
        }

        if(topMarginHeightNormalized >= 0) {
            mLocalVideoLayoutConfig.mY = (int) (topMarginHeightNormalized * 100);
        }
        else { // set the video at the top of the screen
            mLocalVideoLayoutConfig.mY = 0;
        }

        msgDebug+= " VideoHeightRadio="+ratioVideoHeightNormalized+" screenHeight="+screenHeight+" containerHeight="+(int)buttonsContainerHeight+" TopMarginRatio="+mLocalVideoLayoutConfig.mY;
        Log.d(LOG_TAG,msgDebug);
    }

    /**
     * Helper method to start the room activity.
     */
    private void startRoomActivity() {
        if(null != mCall) {
            String roomId = mCall.getRoom().getRoomId();

            Intent intent = new Intent(getApplicationContext(), VectorRoomActivity.class);
            intent.putExtra(VectorRoomActivity.EXTRA_ROOM_ID, roomId);
            intent.putExtra(VectorRoomActivity.EXTRA_MATRIX_ID, mMatrixId);
            startActivity(intent);
        }
    }

    /**
     * Update the mute mic icon according to mute mic status.
     */
    private void refreshMuteMicButton() {
        if ((null != mCall) && mCall.getCallState().equals(IMXCall.CALL_STATE_CONNECTED)) {
            AudioManager audioManager = (AudioManager) VectorCallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
            mMuteMicImageView.setVisibility(View.VISIBLE);

            boolean  isMuted = audioManager.isMicrophoneMute();
            Log.d(LOG_TAG,"## refreshMuteMicButton(): isMuted="+isMuted);

            // update icon
            if (isMuted) {
                mMuteMicImageView.setImageResource(R.drawable.ic_material_mic_off_pink_red); // ic_material_mic_grey
            } else {
                mMuteMicImageView.setImageResource(R.drawable.ic_material_mic_off_grey);
            }
        } else {
            Log.d(LOG_TAG,"## refreshMuteMicButton(): View.INVISIBLE");
            mMuteMicImageView.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Update the mute speaker icon according to speaker status.
     */
    private void refreshSpeakerButton() {
        if ((null != mCall) && mCall.getCallState().equals(IMXCall.CALL_STATE_CONNECTED)) {
            mSpeakerSelectionView.setVisibility(View.VISIBLE);

            AudioManager audioManager = (AudioManager) VectorCallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager.isSpeakerphoneOn()) {
                mSpeakerSelectionView.setImageResource(R.drawable.ic_material_speaker_phone_pink_red); // ic_material_call_grey
            } else {
                mSpeakerSelectionView.setImageResource(R.drawable.ic_material_speaker_phone_grey);
            }
            VectorCallViewActivity.this.setVolumeControlStream(audioManager.getMode());

        } else {
            Log.d(LOG_TAG,"## refreshSpeakerButton(): View.INVISIBLE");
            mSpeakerSelectionView.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * hangup the call.
     */
    private void onHangUp() {
        mSavedCallview = null;

        if (null != mCall) {
            mCall.hangup("");
        }
    }

    /**
     * Manage the UI according to call state.
     */
    private void manageSubViews() {
        // sanity check
        // the call could have been destroyed between call.
        if (null == mCall) {
            Log.d(LOG_TAG, "## manageSubViews(): call instance = null, just return");
            return;
        }

        String callState = mCall.getCallState();
        Log.d(LOG_TAG, "## manageSubViews() IN callState : " + callState);

        // read speaker value from preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isSpeakerPhoneOn = mCall.isVideo();

        // avatar visibility: video call => hide avatar, audio call => show avatar
        mAvatarView.setVisibility((callState.equals(IMXCall.CALL_STATE_CONNECTED) && mCall.isVideo()) ? View.GONE : View.VISIBLE);

        // update UI icon settings
        refreshSpeakerButton();
        refreshMuteMicButton();

        // display the hang up button according to the call state
        switch (callState) {
            case IMXCall.CALL_STATE_ENDED:
                mHangUpImageView.setVisibility(View.INVISIBLE);
                break;
            case IMXCall.CALL_STATE_CONNECTED:
                mHangUpImageView.setVisibility(View.VISIBLE);
                break;
            default:
                mHangUpImageView.setVisibility(View.VISIBLE);
                break;
        }

        // callview visibility management
        if (mCall.isVideo() && !callState.equals(IMXCall.CALL_STATE_ENDED)) {
            int visibility;

            switch (callState) {
                case IMXCall.CALL_STATE_WAIT_CREATE_OFFER:
                case IMXCall.CALL_STATE_INVITE_SENT:
                case IMXCall.CALL_STATE_RINGING:
                case IMXCall.CALL_STATE_CREATE_ANSWER:
                case IMXCall.CALL_STATE_CONNECTING:
                case IMXCall.CALL_STATE_CONNECTED:
                    visibility = View.VISIBLE;
                    break;
                default:
                    visibility = View.GONE;
                    break;
            }

            if ((null != mCall) && (visibility != mCall.getVisibility())) {
                mCall.setVisibility(visibility);
            }
        }

        // ringing management
        switch (callState) {
            case IMXCall.CALL_STATE_CONNECTING:
            case IMXCall.CALL_STATE_CREATE_ANSWER:
            case IMXCall.CALL_STATE_WAIT_LOCAL_MEDIA:
            case IMXCall.CALL_STATE_WAIT_CREATE_OFFER:
                CallRingManager.stopRinging(this);
                break;

            case IMXCall.CALL_STATE_CONNECTED:
                CallRingManager.stopRinging(this);
                final boolean fIsSpeakerPhoneOn = isSpeakerPhoneOn;
                VectorCallViewActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        MXCallsManager.setCallSpeakerphoneOn(VectorCallViewActivity.this, fIsSpeakerPhoneOn);
                        refreshSpeakerButton();
                    }
                });
                break;

            case IMXCall.CALL_STATE_RINGING:
                if (mAutoAccept) {
                    mAutoAccept = false;
                    mCall.answer();
                } else {
                    if (mCall.isIncoming()) {
                        CallRingManager.startRinging(VectorCallViewActivity.this);
                    } else {
                        CallRingManager.startRingBackSound(VectorCallViewActivity.this);
                    }
                }
                break;

            default:
                // nothing to do..
                break;
        }
        Log.d(LOG_TAG, "## manageSubViews(): OUT");
    }

    private void saveCallView() {
        if ((null != mCall) && !mCall.getCallState().equals(IMXCall.CALL_STATE_ENDED) && (null != mCallView) && (null != mCallView.getParent())) {
            ViewGroup parent = (ViewGroup) mCallView.getParent();
            parent.removeView(mCallView);
            mSavedCallview = mCallView;

            EventStreamService.getInstance().displayCallInProgressNotification(mSession, mCall.getRoom(), mCall.getCallId());
            mCallView = null;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);
        saveCallView();
        instance = null;

        // save audio settings
        AudioManager audioManager = (AudioManager) VectorCallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
        if (null != audioManager) {
            savedInstanceState.putBoolean(KEY_MIC_MUTE_STATUS, audioManager.isMicrophoneMute());
            savedInstanceState.putBoolean(KEY_SPEAKER_VIDEO_CALL_STATUS, audioManager.isSpeakerphoneOn());
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (null != mCall) {
            mCall.removeListener(mListener);
        }

        if (mIsCallEnded) {
            Log.d(LOG_TAG, "onDestroy: Hide the call notifications");
            EventStreamService.getInstance().hideCallNotifications();
            CallRingManager.startEndCallSound(this);
        }

        super.onDestroy();
    }
}
