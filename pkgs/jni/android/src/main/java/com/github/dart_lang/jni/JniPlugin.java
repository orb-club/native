// Copyright (c) 2022, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.github.dart_lang.jni;

import android.app.Activity;
import android.content.Context;
import androidx.annotation.NonNull;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

public class JniPlugin implements FlutterPlugin, ActivityAware {

  private static boolean initialized;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    setup(binding.getApplicationContext());
  }

  private void setup(Context context) {
    // Native references, locks, and TLS keys belong to the process, not an
    // individual Flutter engine. Reinitializing overwrites live native state.
    synchronized (JniPlugin.class) {
      if (!initialized) {
        initializeJni(context, JniPlugin.class.getClassLoader());
        initialized = true;
      }
    }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {}

  // Activity handling methods
  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    Activity activity = binding.getActivity();
    setJniActivity(activity, activity.getApplicationContext());
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {}

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    Activity activity = binding.getActivity();
    setJniActivity(activity, activity.getApplicationContext());
  }

  @Override
  public void onDetachedFromActivity() {}

  native void initializeJni(Context context, ClassLoader classLoader);

  native void setJniActivity(Activity activity, Context context);

  static {
    System.loadLibrary("dartjni");
  }
}
