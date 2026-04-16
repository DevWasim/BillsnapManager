# BillSnap Manager - Comprehensive Feature Specification Report

This document outlines the detailed feature set, architecture, and capabilities of the **BillSnap Manager** Android application, based on a comprehensive codebase analysis.

## 1. Application Overview
BillSnap Manager is a robust Android application designed primarily for small shop owners to digitally capture, manage, analyze, and sync their bills and customer transactions. It integrates modern Android technologies like CameraX, Room Database, WorkManager, Jetpack Navigation, Google Drive API, and local On-Device OCR.

---

## 2. Core Functional Modules

### 2.1. Bill Capture & Processing (Camera & OCR)
- **In-App Camera (CameraX):** Real-time camera preview for capturing bill images with edge-to-edge styling and flash control.
- **On-Device OCR Integration:** Utilizes `PaddleOcrManager` and `OcrProcessor` to automatically extract text from captured bill images in real-time or post-processing, facilitating quick data entry.
- **Full Image Preview & Detail:** Users can tap to view the high-resolution scanned document via `FullImageFragment` and visually overlay `OcrTextFragment` to see extracted text.

### 2.2. Bill Management (Gallery & Detail)
- **Gallery Grid View:** A RecyclerView-based grid (`GalleryFragment`) providing an overview of all captured bills.
- **Live Search & Filtering:** Users can dynamically search for bills by customer name or notes.
- **Swipe-to-Action:** Built-in `SwipeCallback` allowing users to swipe bills in the gallery to quickly change their payment status or delete them.
- **Detail View & Editing:** `DetailFragment` and `SaveFormFragment` allow manual editing of bill metadata (Amount, Name, Notes, Payment Status: Paid/Unpaid).

### 2.3. Dashboard & Analytics
- **Interactive Monthly Overview:** `DashboardFragment` provides an at-a-glance financial summary, complete with a custom `BarChartView`.
- **Top Customers Tracking:** The dashboard dynamically highlights top customers based on transaction frequency/volume using `TopCustomersAdapter`.
- **Drill-down Analytics:** Interactive elements where clicking on a specific month reveals its complete associated bill details via `MonthBillAdapter`.

### 2.4. Customer Management (Profile Module)
- **Customer Directory:** `ProfilesFragment` lists all registered clients.
- **Detailed Profiles:** `ProfileDetailFragment` showcases individual customer histories, aggregated bill data, and payment status tracking. 
- **Customer Creation:** Forms to cleanly add and define new customers (`AddCustomerFragment`).

### 2.5. Multi-User & Role-Based Access (Workers Module)
- **Role System:** Supports `Owner` and `Worker` tiers for multi-user shop access.
- **Worker Invites:** `InviteWorkerFragment` generates deep-links (https://billsnap.com/invite) to onboard new employees easily.
- **Worker Approval Flow:** `AcceptInviteFragment` processes invites. The home dashboard restricts access for "Pending" workers until the Owner approves them in `WorkerDetailFragment`.
- **Audit Logging:** `WorkerLogsFragment` tracks the specific actions taken by workers (e.g., creating a bill, modifying a status) for accountability.

---

## 3. Background Services & Capabilities

### 3.1. Cloud Sync & Backup
- **Google Drive Integration:** `DriveManager` handles seamless metadata and image backup/restore using the Google Drive APi ensuring data safety.
- **Cloud Sync Manager:** `CloudSyncManager` coordinates bidirectional syncing between the local Room database and cloud storage (potentially handling Firestore for multi-user sync).

### 3.2. Automated Workers (WorkManager)
- **OverdueCheckWorker:** Periodically scans the database for scheduled bills that have surpassed their due date without payment.
- **ReminderWorker:** Dispatches local Android Push Notifications to the shop owner or customer for pending dues.
- **SyncWorker:** Ensures background data consistency by uploading the newest local, offline changes to the cloud when the network is available.

---

## 4. Security & Export Utilities

### 4.1. Privacy & Security
- **Biometric App Lock:** Integration of `android.permission.USE_BIOMETRIC` to lock the application or sensitive fragments behind Fingerprint/Face unlock.
- **Scoped Storage:** Proper handling of image saving avoiding global external storage clutter.

### 4.2. Reporting Utilities
- **PDF Exporter:** The `PdfExporter` utility allows owners to export comprehensive financial statements or individual bill receipts directly to a formatted PDF document.
- **ZIP Backup:** Complete system export feature bundling images and Room metadata into a single `.zip` archive for cold storage.

---

## 5. UI/UX & Theming
- **Premium Dark Theme:** App-wide enforced `Theme.Material3.Dark.NoActionBar` utilizing a curated dark palette (`#0F1117` background, `#7C4DFF` primary purple) applied to all components including system bars.
- **MVVM Architecture:** Clean separation of UI logic using modern `ViewModels` for every major fragment ensuring configuration changes survive cleanly without data loss.

---
*Report generated via static analysis of the BillSnap Manager workspace.*
