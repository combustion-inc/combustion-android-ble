# Android Communication Library for Combustion Inc.

[![License](https://img.shields.io/github/license/combustion-inc/combustion-android-ble?color=red)](LICENSE)
[![Release](https://img.shields.io/github/v/release/combustion-inc/combustion-android-ble?color=red&include_prereleases)](https://jitpack.io/#combustion-inc/combustion-android-ble)
[![Twitter](https://img.shields.io/badge/Twitter-@inccombustion-blue.svg?style=flat)](https://twitter.com/intent/tweet?screen_name=inccombustion)

## About Combustion Inc.

We build nice things that make cooking more enjoyable. Like a thermometer that's wireless, oven-safe, and uses machine learning to do what no other thermometer can: predict your food’s cooking and resting times with uncanny accuracy. 

Our Predictive Thermometer's eight temperature sensors measure the temp outside and inside the food, in the center and at the surface, and nearly everywhere in between. So you know what’s really happening in and around your food. There's a display Timer that's big and bold—legible even through tears of joy and chopped onions—and a mobile app. 

Or you can create your own mobile app to work with the Predictive Thermometer using this and our [iOS](https://github.com/combustion-inc/combustion-ios-ble) open source libraries.

Visit [www.combustion.inc](https://www.combustion.inc) to sign up to be notified when they're available to order in early 2022.

Head on over to our [FAQ](https://combustion.inc/pages/faq) for more product details.

Ask us a quick question on [Twitter](https://twitter.com/intent/tweet?screen_name=inccombustion).

Email [hello@combustion.inc](mailto:hello@combustion.inc) for OEM partnership information.

## Overview
This project enables BLE communication with Combustion Inc. Predictive Thermometers using Android smartphones.  The library is written in [Kotlin targeting Android OS](https://developer.android.com/kotlin) and is distributed as an [Android Archive](https://developer.android.com/studio/projects/android-library) for incorporation into your apps.  

The library provides an [Android Service](https://developer.android.com/guide/components/services) with a simple API to discover and receive temperature data from Combustion Inc. thermometers.  The library uses [Kotlin flows](https://developer.android.com/kotlin/flow) as the communication mechanism to deliver data and state asynchronously to your app's UI components.

The library allows third-party apps to
- Scan for and be notified of in-range thermometers.
- Receive real-time temperature updates from thermometers.
- Connect to and transfer the temperature logs from thermometers.
- Be notified of global events such as device connection, disconnection, or Bluetooth enable and disable.

There are more features on our roadmap below.  So please explore and let us know what you think!

## Using the Library

A straightforward example Android app illustrating the use of this framework is available at the [combustion-android-example](https://github.com/combustion-inc/combustion-android-example) repo.  The example uses [Jetpack Compose](https://developer.android.com/jetpack/compose) and follows Android's [Guide to App Architecture](https://developer.android.com/jetpack/guide#ui-layer) with example UI Layer code for interacting with this library as the Data Layer.

## The API

The public API and data access objects are contained in the [inc.combustion.framework.service](combustion-android-ble/src/main/java/inc/combustion/framework/service) package.  The [`DeviceManager`](combustion-android-ble/src/main/java/inc/combustion/framework/service/DeviceManager.kt) class is the primary entrypoint for the API.  See the [`DeviceManager`](combustion-android-ble/src/main/java/inc/combustion/framework/service/DeviceManager.kt) source documentation for more details on the API.

## Adding the Library to Your Project
You can find our library on [JitPack](https://jitpack.io/#combustion-inc/combustion-android-ble).  Go to that page for instructions on setting up your root `build.gradle` to use the JitPack repository and add the library dependency to your build.  See our [`build.gradle`](combustion-android-ble/build.gradle) for the compiler arguments and dependencies used by the library.

## Permissions
The library uses BLE to communicate with Combustion's thermometers so your app is required to declare [Bluetooth permissions](https://developer.android.com/guide/topics/connectivity/bluetooth/permissions).  The [Android example](https://github.com/combustion-inc/combustion-android-example) walks through an approach for getting consent from your users as part of your app's UI.  Also see the library's [AndroidManifest.xml](combustion-android-ble/src/main/AndroidManifest.xml) for the specific permission list.

## Feature Requests & Issues
Your feedback is important.  For requesting new features or reporting issues use the [issue tracker](https://github.com/combustion-inc/combustion-android-ble/issues).  

## Framework Features Coming Soon

The following features are planned for near-term development:

- **Set ring color**: Methods for setting a probe's identifying silicone ring color  (colors TBA).
- **Set numeric ID**: Methods for setting a probe's numeric ID (1-8).
- **Firmware update**: Methods for updating a Probe's firmware with a signed firmware image.
- **Instant Read**: Receiving Instant Read temperature updates in real-time from the probe.
