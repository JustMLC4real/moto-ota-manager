/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

// Source: AOSP system/update_engine commit 4591637f51644f5429f9e2fe589ab22b92f61842,
// binder_bindings/android/os/IUpdateEngineCallback.aidl. The platform-only
// @UnsupportedAppUsage annotation is omitted for public-SDK compilation. This
// annotation has no Binder wire effect; method order, types and oneway are unchanged.

/** @hide */
oneway interface IUpdateEngineCallback {
  /** @hide */
  void onStatusUpdate(int status_code, float percentage);
  /** @hide */
  void onPayloadApplicationComplete(int error_code);
}
