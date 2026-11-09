# BatchRename

A native Kotlin Android app that renames a group of files using the first selected file as a naming template.

## How it works

1. Choose one file as the template, e.g. `9 Nov Patna Food Safety Department Raid 1.mp4`.
2. Choose the remaining files using the Android document picker. Their order in the picker's selection is used.
3. Review the preview.
4. Tap **Rename all files**.

The template stays unchanged. The other files become `9 Nov Patna Food Safety Department Raid 2.mp4`, `... 3.mp4`, etc. Each file keeps its own extension.

## Build on GitHub

1. Create a GitHub repository named `BatchRename`.
2. Upload the contents of this ZIP to the repository root.
3. Open **Actions** and enable workflows if GitHub asks.
4. Run **Build BatchRename APK** from the Actions tab.
5. Download the `BatchRename-debug-apk` artifact from the successful run.

## Notes

- Minimum Android version: Android 7.0 (API 24).
- No broad storage permission is requested. The app uses Android's Storage Access Framework.
- Renaming depends on the selected document provider supporting `DocumentsContract.renameDocument`. Some cloud providers or restricted locations may reject rename operations.
- The app previews names before applying them. The first selected template file is never renamed.
