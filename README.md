# Random Scene App

This Android application loads a collection of scenes from a JSON file and randomly displays them to the user.

## Features

- Loads scenes from a JSON file in the assets folder
- Displays a random scene when the "Random Scene" button is clicked
- Renders markdown links as clickable HTML links
- Smooth animations when transitioning between scenes

## Getting Started

1. Open the project in Android Studio
2. Sync Gradle files
3. Run the app on an emulator or physical device

## Technical Details

- Minimum Android SDK: 21 (Android 5.0 Lollipop)
- Target Android SDK: 33 (Android 13)
- Kotlin version: 1.8.0

## Dependencies

- AndroidX Core KTX: 1.9.0
- AndroidX AppCompat: 1.6.1
- Material Components: 1.8.0
- ConstraintLayout: 2.1.4
- CardView: 1.0.0

## Project Structure

- **app/src/main/java/com/example/randomsceneapp/**
  - `MainActivity.kt`: Main activity that handles loading and displaying scenes
  - `Scene.kt`: Data class for the scene model

- **app/src/main/res/**
  - **layout/**
    - `activity_main.xml`: Layout for the main activity
  - **values/**
    - `colors.xml`: Color definitions
    - `strings.xml`: String resources
    - `themes.xml`: App theme configuration
  - **anim/**
    - `fade_in.xml`: Animation for fading in
    - `fade_out.xml`: Animation for fading out

- **app/src/main/assets/**
  - `scenes.json`: JSON file containing the scenes data
