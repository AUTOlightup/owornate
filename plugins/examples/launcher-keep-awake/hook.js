'use strict';

// launcher-v1 factory body. The runtime supplies plugin.id, plugin.context and
// plugin.report(error); this file is already executing inside Java.perform().
const KEEP_SCREEN_ON = 128;
const LAUNCHER_PACKAGE = 'com.heytap.wearable.launcher';
const Launcher = Java.use('com.heytap.wearable.launcher.Launcher');
const originalOnResume = Launcher.onResume;
const originalOnPause = Launcher.onPause;

function report(error) {
  plugin.report(error);
}

function isLauncherActivity(activity) {
  return String(activity.getPackageName()) === LAUNCHER_PACKAGE;
}

originalOnResume.implementation = function () {
  const activity = this;
  try {
    originalOnResume.call(activity);
  } catch (error) {
    report(error);
    throw error;
  }
  try {
    if (isLauncherActivity(activity)) activity.getWindow().addFlags(KEEP_SCREEN_ON);
  } catch (error) { report(error); }
};

originalOnPause.implementation = function () {
  const activity = this;
  try {
    originalOnPause.call(activity);
  } catch (error) {
    report(error);
    throw error;
  }
  try {
    if (isLauncherActivity(activity)) activity.getWindow().clearFlags(KEEP_SCREEN_ON);
  } catch (error) { report(error); }
};
