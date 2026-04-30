# Firebase Setup Rules (Recommended)

Use these as a starting point for Firestore security.

## Firestore Rules

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    match /users/{userId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;

      match /rss_links/{rssId} {
        allow read, write: if request.auth != null && request.auth.uid == userId;
      }
    }
  }
}
```

## Why
- Each user can access only their own profile and RSS links.
- Prevents cross-user data leakage.

## Firebase Auth
Enable:
- Authentication > Sign-in method > Google

## Important
- Place `google-services.json` at `app/google-services.json`
- Add SHA-1 and SHA-256 in Firebase Android app settings for Google login to work properly in release/debug builds.
