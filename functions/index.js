// functions/index.js
const { onObjectFinalized } = require("firebase-functions/v2/storage"); // Import v2 storage trigger
const { onValueCreated, onValueWritten } = require("firebase-functions/v2/database"); // For RTDB v2 triggers
const { logger } = require("firebase-functions"); // Import v2 logger
const admin = require('firebase-admin');
const { onDocumentDeleted } = require("firebase-functions/v2/firestore"); // For Firestore v2 triggers

admin.initializeApp();

const firestore = admin.firestore();
const storageAdmin = admin.storage(); // Use admin.storage() for operations like delete
const messaging = admin.messaging(); // Initialize messaging

// Define the expected path prefix in Storage where images will trigger this function.
const EXPECTED_STORAGE_PATH_PREFIX = "position_images/"; // From your PositionsRepository.kt

// Define the bucket if it's not the default one.
// If it's the default bucket, you might not need to specify it explicitly here.
// const BUCKET_NAME = 'your-project-id.appspot.com'; // Or your specific bucket name

// IMPORTANT: You have two functions exported with the same name: createPositionFromUploadedImage.
// You'll need to rename or remove one of them for deployment to work correctly.
// For this example, I'm commenting out the second one.

exports.createPositionFromUploadedImage = onObjectFinalized(
    {
        // bucket: BUCKET_NAME, // Uncomment and set if not the default bucket
        // memory: "256MiB", // Optional: configure memory
        // timeoutSeconds: 60, // Optional: configure timeout
    },
    async (event) => {
        const fileObject = event.data; // The Storage object
        const filePath = fileObject.name; // File path in the bucket.
        const contentType = fileObject.contentType;
        const bucketName = fileObject.bucket;

        logger.log(`File ${filePath} uploaded to bucket ${bucketName}. ContentType: ${contentType}`);

        // 1. Validate Trigger Path and Content Type
        if (!filePath.startsWith(EXPECTED_STORAGE_PATH_PREFIX)) {
            logger.log(`File ${filePath} is not in the expected path '${EXPECTED_STORAGE_PATH_PREFIX}'. Ignoring.`);
            return null;
        }
        if (!contentType || !contentType.startsWith('image/')) {
            logger.log(`File ${filePath} is not an image. ContentType: ${contentType}. Ignoring.`);
            return null;
        }

        const file = storageAdmin.bucket(bucketName).file(filePath);

        // 2. Get Image Download URL and Custom Metadata
        let downloadURL;
        let customMetadata;
        try {
            const signedUrls = await file.getSignedUrl({
                action: 'read',
                expires: '03-09-2491'
            });
            downloadURL = signedUrls[0];
            
            const [metadataFromFile] = await file.getMetadata();
            customMetadata = metadataFromFile.metadata || {};
            logger.log(`File: ${filePath}, Download URL: ${downloadURL}, Custom Metadata:`, customMetadata);
        } catch (error) {
            logger.error(`Error getting metadata or download URL for ${filePath}:`, error);
            logger.log(`Deleting orphaned file ${filePath} due to metadata/URL error.`);
            await file.delete().catch(delErr => logger.error(`Failed to delete orphaned file ${filePath} after metadata error:`, delErr));
            return null;
        }

        // 3. Extract Data from Metadata
        const positionName = customMetadata.positionName;
        const originalUserId = customMetadata.originalUserId;
        const isFavoriteStr = customMetadata.isFavorite;
        const isAssetStr = customMetadata.isAsset;

        if (!positionName || !originalUserId) {
            logger.error(`Missing critical metadata (positionName or originalUserId) for ${filePath}. Metadata:`, customMetadata);
            logger.log(`Deleting orphaned file ${filePath} due to missing critical metadata.`);
            await file.delete().catch(delErr => logger.error(`Failed to delete orphaned file ${filePath} after missing metadata:`, delErr));
            return null;
        }

        // 4. Construct PositionItem for Firestore
        const positionItemData = {
            name: positionName,
            imageName: downloadURL,
            isAsset: isAssetStr === 'true',
            userId: originalUserId,
            isFavorite: isFavoriteStr === 'true',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
        };

        // 5. Write to Firestore
        try {
            // Idempotent write: Check if position already exists for this user and name
            const positionsRef = firestore.collection('positions');
            const querySnapshot = await positionsRef
                .where('userId', '==', originalUserId)
                .where('name', '==', positionName)
                .limit(1)
                .get();

            if (!querySnapshot.empty) {
                const existingDoc = querySnapshot.docs[0];
                logger.log(`Position '${positionName}' for user '${originalUserId}' already exists (ID: ${existingDoc.id}). Updating.`);
                await existingDoc.ref.update({
                    ...positionItemData, // Spread new data
                    imageName: downloadURL, // Ensure URL is updated if image is re-uploaded
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
                logger.log(`Successfully updated Firestore document ${existingDoc.id} for position '${positionName}'.`);
            } else {
                logger.log(`Position '${positionName}' for user '${originalUserId}' does not exist. Creating new document.`);
                const docRef = await positionsRef.add({
                    ...positionItemData,
                    createdAt: admin.firestore.FieldValue.serverTimestamp()
                });
                logger.log(`Successfully created Firestore document ${docRef.id} for position '${positionName}'.`);
            }
        } catch (error) {
            logger.error(`Error writing/updating position '${positionName}' to Firestore for ${filePath}:`, error);
            logger.log(`Deleting orphaned file ${filePath} due to Firestore write/update failure.`);
            await file.delete().catch(delErr => logger.error(`Failed to delete orphaned file ${filePath} after Firestore error:`, delErr));
        }
        return null;
    }
);

/* // Commenting out the duplicated function. Please resolve this in your actual deployment.
exports.createPositionFromUploadedImage = onObjectFinalized(
    {
        // bucket: BUCKET_NAME, // Your bucket settings
        // memory: "256MiB",
        // timeoutSeconds: 60,
    },
    async (event) => {
        // ... (implementation of the second createPositionFromUploadedImage)
    }
);
*/

exports.deletePositionImageFromStorage = onDocumentDeleted(
    "positions/{positionId}",
    async (event) => {
        const deletedPositionData = event.data.data();
        const positionId = event.params.positionId;

        if (!deletedPositionData) {
            logger.log(`DEL_IMG: No data for deleted position ID: ${positionId}.`);
            return null;
        }

        const imageName = deletedPositionData.imageName;

        if (!imageName || typeof imageName !== 'string') {
            logger.log(`DEL_IMG: Position ID: ${positionId} - imageName missing or not a string.`);
            return null;
        }

        logger.log(`DEL_IMG: Position ID: ${positionId} - Received imageName URL: ${imageName}`);

        let filePath;
        try {
            const url = new URL(imageName);
            
            if (url.hostname === "storage.googleapis.com" && url.pathname) {
                const pathParts = url.pathname.split('/');
                if (pathParts.length > 2) { 
                    filePath = pathParts.slice(2).join('/');
                    logger.log(`DEL_IMG: Position ID: ${positionId} - Parsed path (from storage.googleapis.com URL): ${filePath}`);
                }
            } else if (url.hostname === "firebasestorage.googleapis.com" && url.pathname.includes("/o/")) {
                const decodedPathName = decodeURIComponent(url.pathname);
                const match = decodedPathName.match(/\/o\/(.+)/);
                if (match && match[1]) {
                    filePath = match[1];
                     logger.log(`DEL_IMG: Position ID: ${positionId} - Parsed path (from firebasestorage.googleapis.com URL): ${filePath}`);
                }
            }

            if (!filePath) {
                logger.warn(`DEL_IMG: Position ID: ${positionId} - Could not determine file path from URL: ${imageName}`);
                return null;
            }
        } catch (e) {
            logger.warn(`DEL_IMG: Position ID: ${positionId} - Error parsing imageName URL '${imageName}':`, e.message);
            return null;
        }

        try {
            logger.log(`DEL_IMG: Position ID: ${positionId} - Attempting to delete image from Storage at path: ${filePath}`);
            const bucket = storageAdmin.bucket(); 
            const file = bucket.file(filePath);

            await file.delete();
            logger.log(`DEL_IMG: Position ID: ${positionId} - Successfully deleted image ${filePath} from Storage.`);
        } catch (error) {
            if (error.code === 404) {
                logger.warn(`DEL_IMG: Position ID: ${positionId} - Image not found in Storage (path: ${filePath}). It might have been already deleted. Error:`, error.message);
            } else {
                logger.error(`DEL_IMG: Position ID: ${positionId} - Error deleting image ${filePath} from Storage:`, error);
            }
        }
        return null;
    }
);

// New function to send notifications for new tasks
exports.sendTaskNotification = onValueCreated("shared_task_lists/{pairingId}/tasks/{taskId}", async (event) => {
    const snapshot = event.data;
    const taskData = snapshot.val();
    const params = event.params;

    if (!taskData) {
        logger.log("No data associated with the event for sendTaskNotification. Exiting.");
        return null;
    }

    const createdByUid = taskData.createdByUid;
    const pairingId = params.pairingId;
    const taskTitle = taskData.title || "a new task";

    if (!createdByUid || !pairingId) {
        logger.error("Missing createdByUid or pairingId in task data or params.", { taskData, params });
        return null;
    }

    const uidsInPair = pairingId.split('_');
    if (uidsInPair.length !== 2) {
        logger.error(`Invalid pairingId format: ${pairingId}`);
        return null;
    }

    const recipientUid = uidsInPair.find(uid => uid !== createdByUid);
    if (!recipientUid) {
        logger.error(`Could not determine recipient UID from pairingId ${pairingId} and creator ${createdByUid}`);
        return null;
    }

    try {
        // Fetch recipient's profile (for FCM token)
        const recipientProfileDoc = await firestore.collection('users').doc(recipientUid).get();
        if (!recipientProfileDoc.exists) {
            logger.warn(`Recipient profile not found for UID: ${recipientUid}`);
            return null;
        }
        const recipientProfile = recipientProfileDoc.data();
        const recipientFcmToken = recipientProfile.fcmToken;

        if (!recipientFcmToken) {
            logger.warn(`Recipient ${recipientUid} does not have an FCM token.`);
            return null;
        }

        // Fetch creator's profile (for role)
        const creatorProfileDoc = await firestore.collection('users').doc(createdByUid).get();
        let creatorName = "Your Partner"; // Default name
        if (creatorProfileDoc.exists) {
            const creatorProfile = creatorProfileDoc.data();
            // Use displayName if available, otherwise fallback to a generic term or role
            creatorName = creatorProfile.displayName || creatorProfile.role || "Your Partner";
        } else {
            logger.warn(`Creator profile not found for UID: ${createdByUid}`);
        }
        
        const notificationTitle = `New Task from ${creatorName}`;
        const notificationBody = `${creatorName} has assigned you a new task: "${taskTitle}"`;
        
        const payload = {
            notification: {
                title: notificationTitle,
                body: notificationBody,
            },
            token: recipientFcmToken,
            data: { // Optional: send data payload for client-side handling
                taskId: params.taskId,
                pairingId: pairingId,
                type: "NEW_TASK"
            }
        };

        logger.log(`Sending new task notification to ${recipientUid} (token: ${recipientFcmToken.substring(0,10)}...) for task: "${taskTitle}"`);
        await messaging.send(payload);
        logger.log("Successfully sent new task message to:", recipientUid);
        return null;

    } catch (error) {
        logger.error("Error sending new task notification:", error);
        return null;
    }
});

// Function to send notification when a task is marked as completed
exports.sendTaskCompletionNotification = onValueWritten("shared_task_lists/{pairingId}/tasks/{taskId}", async (event) => {
    const beforeData = event.data.before.val();
    const afterData = event.data.after.val();
    const params = event.params;

    // If task was deleted, or didn't exist before (it's a new task, handled by sendTaskNotification)
    if (!event.data.after.exists() || !event.data.before.exists()) {
        logger.log("Task completion: No data after write or no data before write (likely creation/deletion). Exiting.", 
                   {afterExists: event.data.after.exists(), beforeExists: event.data.before.exists()});
        return null;
    }

    // Log the full before and after data for debugging
    logger.log("Task completion check. TaskId:", params.taskId, "PairingId:", params.pairingId);
    logger.log("Before data:", JSON.stringify(beforeData));
    logger.log("After data:", JSON.stringify(afterData));
    
    // Check if 'completed' changed to true from a different state
    if (afterData.completed === true && beforeData.completed !== true) {
        logger.log(`Task ${params.taskId} in pairing ${params.pairingId} marked as complete (changed from ${beforeData.completed} to ${afterData.completed}).`);

        const taskTitle = afterData.title || "A task";
        const createdByUid = afterData.createdByUid; // This is the Dom
        const completedByUid = afterData.completedByUid; // This should be the Sub, if you add this field

        if (!createdByUid) {
            logger.error(`Task ${params.taskId} is missing createdByUid. Cannot notify.`);
            return null;
        }

        // The recipient of this notification is the Dom (createdByUid)
        const domUid = createdByUid;

        try {
            // Fetch Dom's profile for FCM token
            const domProfileDoc = await firestore.collection('users').doc(domUid).get();
            if (!domProfileDoc.exists) {
                logger.warn(`Dom profile not found for UID: ${domUid} for task completion.`);
                return null;
            }
            const domProfile = domProfileDoc.data();
            const domFcmToken = domProfile.fcmToken;

            if (!domFcmToken) {
                logger.warn(`Dom ${domUid} does not have an FCM token for task completion.`);
                return null;
            }

            const notificationTitle = `Task Completed!`;
            const notificationBody = `Your Sub has completed the task: "${taskTitle}"`;

            const payload = {
                notification: {
                    title: notificationTitle,
                    body: notificationBody,
                },
                token: domFcmToken,
                data: { // Optional: send data payload for client-side handling
                    taskId: params.taskId,
                    pairingId: params.pairingId,
                    type: "TASK_COMPLETED"
                }
            };

            logger.log(`Sending task completion notification to Dom ${domUid} (token: ${domFcmToken.substring(0,10)}...) for task: "${taskTitle}"`);
            await messaging.send(payload);
            logger.log("Successfully sent task completion message to Dom:", domUid);
            return null;

        } catch (error) {
            logger.error("Error sending task completion notification:", error);
            return null;
        }
    } else {
        logger.log(`Task ${params.taskId} in pairing ${params.pairingId} was updated, but condition for completion notification not met. afterData.completed: ${afterData.completed}, beforeData.completed: ${beforeData.completed}. No completion notification sent.`);
        return null;
    }
});
