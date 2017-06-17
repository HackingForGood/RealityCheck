/**
   * Helper class to manage threading.
   */
  class VideoLoaderTask extends AsyncTask<Pair<Uri, Options>, Void, Boolean> {
    @Override
    protected Boolean doInBackground(Pair<Uri, Options>... fileInformation) {
      try {
         if (fileInformation == null || fileInformation.length < 1
          || fileInformation[0] == null || fileInformation[0].first == null) {
          // No intent was specified, so we default to playing the local stereo-over-under video.
          Options options = new Options();
          options.inputType = Options.TYPE_STEREO_OVER_UNDER;
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
                .makeText(SimpleVrVideoActivity.this, "Error opening file. ", Toast.LENGTH_LONG)
                .show();
          }
        });
        Log.e(TAG, "Could not open video: " + e);
      }

      return true;
    }
  }