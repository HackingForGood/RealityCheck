package com.example.realitycheckapp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.vr.sdk.widgets.video.VrVideoEventListener;
import com.google.vr.sdk.widgets.video.VrVideoView;

import java.io.IOException;

public class VrActivity extends Activity {

	private static final String TAG = VrActivity.class.getSimpleName();

	/**
	 * Preserve the video's state when rotating the phone.
	 */
	private static final String STATE_IS_PAUSED = "isPaused";
	private static final String STATE_PROGRESS_TIME = "progressTime";
	/**
	 * The video duration doesn't need to be preserved, but it is saved in this example. This allows
	 * the seekBar to be configured during {@link #onRestoreInstanceState(Bundle)} rather than waiting
	 * for the video to be reloaded and analyzed. This avoid UI jank.
	 */
	private static final String STATE_VIDEO_DURATION = "videoDuration";

	/**
	 * Arbitrary constants and variable to track load status. In this example, this variable should
	 * only be accessed on the UI thread. In a real app, this variable would be code that performs
	 * some UI actions when the video is fully loaded.
	 */
	public static final int LOAD_VIDEO_STATUS_UNKNOWN = 0;
	public static final int LOAD_VIDEO_STATUS_SUCCESS = 1;
	public static final int LOAD_VIDEO_STATUS_ERROR = 2;

	private int loadVideoStatus = LOAD_VIDEO_STATUS_UNKNOWN;

	/** Tracks the file to be loaded across the lifetime of this app. **/
	private Uri fileUri;

	/** Configuration information for the video. **/
	private VrVideoView.Options videoOptions = new VrVideoView.Options();

	private VideoLoaderTask backgroundVideoLoaderTask;

	/**
	 * The video view and its custom UI elements.
	 */
	protected VrVideoView videoWidgetView;

	/**
	 * Seeking UI & progress indicator. The seekBar's progress value represents milliseconds in the
	 * video.
	 */
	private SeekBar seekBar;
	private TextView statusText;

	private ImageButton volumeToggle;
	private boolean isMuted;

	/**
	 * By default, the video will start playing as soon as it is loaded. This can be changed by using
	 * {@link VrVideoView#pauseVideo()} after loading the video.
	 */
	private boolean isPaused = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// VR setup
		initializeGvrView();
	}

	public void initializeGvrView() {
		setContentView(R.layout.activity_vr);

		seekBar = (SeekBar) findViewById(R.id.seek_bar);
		seekBar.setOnSeekBarChangeListener(new SeekBarListener());
		statusText = (TextView) findViewById(R.id.status_text);

		// Make the source link clickable.
		TextView sourceText = (TextView) findViewById(R.id.source);
		sourceText.setText(Html.fromHtml(getString(R.string.source)));
		sourceText.setMovementMethod(LinkMovementMethod.getInstance());

		// Bind input and output objects for the view.
		videoWidgetView = (VrVideoView) findViewById(R.id.video_view);
		videoWidgetView.setEventListener(new ActivityEventListener());

		volumeToggle = (ImageButton) findViewById(R.id.volume_toggle);
		volumeToggle.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				setIsMuted(!isMuted);
			}
		});

		loadVideoStatus = LOAD_VIDEO_STATUS_UNKNOWN;

		// Initial launch of the app or an Activity recreation due to rotation.
		handleIntent(getIntent());
	}

	public int getLoadVideoStatus() {
		return loadVideoStatus;
	}

	private void setIsMuted(boolean isMuted) {
		this.isMuted = isMuted;
		volumeToggle.setImageResource(isMuted ? R.drawable.volume_off : R.drawable.volume_on);
		videoWidgetView.setVolume(isMuted ? 0.0f : 1.0f);
	}

	public boolean isMuted() {
		return isMuted;
	}

	/**
	 * Load custom videos based on the Intent or load the default video. See the Javadoc for this
	 * class for information on generating a custom intent via adb.
	 */
	private void handleIntent(Intent intent) {
		// Determine if the Intent contains a file to load.
		if (Intent.ACTION_VIEW.equals(intent.getAction())) {
			Log.i(TAG, "ACTION_VIEW Intent received");

			fileUri = intent.getData();
			if (fileUri == null) {
				Log.w(TAG, "No data uri specified. Use \"-d /path/filename\".");
			} else {
				Log.i(TAG, "Using file " + fileUri.toString());
			}

			videoOptions.inputFormat = intent.getIntExtra("inputFormat", VrVideoView.Options.FORMAT_DEFAULT);
			videoOptions.inputType = intent.getIntExtra("inputType", VrVideoView.Options.TYPE_MONO);
		} else {
			Log.i(TAG, "Intent is not ACTION_VIEW. Using the default video.");
			fileUri = null;
		}

		// Load the bitmap in a background thread to avoid blocking the UI thread. This operation can
		// take 100s of milliseconds.
		if (backgroundVideoLoaderTask != null) {
			// Cancel any task from a previous intent sent to this activity.
			backgroundVideoLoaderTask.cancel(true);
		}
		backgroundVideoLoaderTask = new VideoLoaderTask();
		backgroundVideoLoaderTask.execute(Pair.create(fileUri, videoOptions));
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putLong(STATE_PROGRESS_TIME, videoWidgetView.getCurrentPosition());
		savedInstanceState.putLong(STATE_VIDEO_DURATION, videoWidgetView.getDuration());
		savedInstanceState.putBoolean(STATE_IS_PAUSED, isPaused);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onRestoreInstanceState(Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);

		long progressTime = savedInstanceState.getLong(STATE_PROGRESS_TIME);
		videoWidgetView.seekTo(progressTime);
		seekBar.setMax((int) savedInstanceState.getLong(STATE_VIDEO_DURATION));
		seekBar.setProgress((int) progressTime);

		isPaused = savedInstanceState.getBoolean(STATE_IS_PAUSED);
		if (isPaused) {
			videoWidgetView.pauseVideo();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		// Prevent the view from rendering continuously when in the background.
		videoWidgetView.pauseRendering();
		// If the video is playing when onPause() is called, the default behavior will be to pause
		// the video and keep it paused when onResume() is called.
		isPaused = true;
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Resume the 3D rendering.
		videoWidgetView.resumeRendering();
		// Update the text to account for the paused video in onPause().
		updateStatusText();
	}

	@Override
	protected void onDestroy() {
		// Destroy the widget and free memory.
		videoWidgetView.shutdown();
		super.onDestroy();
	}

	private void togglePause() {
		if (isPaused) {
			videoWidgetView.playVideo();
		} else {
			videoWidgetView.pauseVideo();
		}
		isPaused = !isPaused;
		updateStatusText();
	}

	private void updateStatusText() {
		StringBuilder status = new StringBuilder();
		status.append(isPaused ? "Paused: " : "Playing: ");
		status.append(String.format("%.2f", videoWidgetView.getCurrentPosition() / 1000f));
		status.append(" / ");
		status.append(videoWidgetView.getDuration() / 1000f);
		status.append(" seconds.");
		statusText.setText(status.toString());
	}

	/**
	 * When the user manipulates the seek bar, update the video position.
	 */
	private class SeekBarListener implements SeekBar.OnSeekBarChangeListener {
		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (fromUser) {
				videoWidgetView.seekTo(progress);
				updateStatusText();
			} // else this was from the ActivityEventHandler.onNewFrame()'s seekBar.setProgress update.
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) { }

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) { }
	}

	/**
	 * Listen to the important events from widget.
	 */
	private class ActivityEventListener extends VrVideoEventListener {
		/**
		 * Called by video widget on the UI thread when it's done loading the video.
		 */
		@Override
		public void onLoadSuccess() {
			Log.i(TAG, "Successfully loaded video " + videoWidgetView.getDuration());
			loadVideoStatus = LOAD_VIDEO_STATUS_SUCCESS;
			seekBar.setMax((int) videoWidgetView.getDuration());
			updateStatusText();
		}

		/**
		 * Called by video widget on the UI thread on any asynchronous error.
		 */
		@Override
		public void onLoadError(String errorMessage) {
			// An error here is normally due to being unable to decode the video format.
			loadVideoStatus = LOAD_VIDEO_STATUS_ERROR;
			Toast.makeText(
					VrActivity.this, "Error loading video: " + errorMessage, Toast.LENGTH_LONG)
					.show();
			Log.e(TAG, "Error loading video: " + errorMessage);
		}

		@Override
		public void onClick() {
			togglePause();
		}

		/**
		 * Update the UI every frame.
		 */
		@Override
		public void onNewFrame() {
			updateStatusText();
			seekBar.setProgress((int) videoWidgetView.getCurrentPosition());
		}

		/**
		 * Make the video play in a loop. This method could also be used to move to the next video in
		 * a playlist.
		 */
		@Override
		public void onCompletion() {
			videoWidgetView.seekTo(0);
		}
	}

	/**
	 * Helper class to manage threading.
	 */
	class VideoLoaderTask extends AsyncTask<Pair<Uri, VrVideoView.Options>, Void, Boolean> {
		@Override
		protected Boolean doInBackground(Pair<Uri, VrVideoView.Options>... fileInformation) {
			try {
				if (fileInformation == null || fileInformation.length < 1
						|| fileInformation[0] == null || fileInformation[0].first == null) {
					// No intent was specified, so we default to playing the local stereo-over-under video.
					VrVideoView.Options options = new VrVideoView.Options();
					options.inputType = VrVideoView.Options.TYPE_STEREO_OVER_UNDER;
					videoWidgetView.loadVideoFromAsset("congo.mp4", options);
				} else {
					videoWidgetView.loadVideo(fileInformation[0].first, fileInformation[0].second);
				}
			} catch (IOException e) {
				// An error here is normally due to being unable to locate the file.
				loadVideoStatus = LOAD_VIDEO_STATUS_ERROR;
				// Since this is a background thread, we need to switch to the main thread to show a toast.
				videoWidgetView.post(new Runnable() {
					@Override
					public void run() {
						Toast
								.makeText(VrActivity.this, "Error opening file. ", Toast.LENGTH_LONG)
								.show();
					}
				});
				Log.e(TAG, "Could not open video: " + e);
			}

			return true;
		}
	}
}
