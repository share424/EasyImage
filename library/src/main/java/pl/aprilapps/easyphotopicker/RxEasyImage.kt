package pl.aprilapps.easyphotopicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.fragment.app.Fragment
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import rx_activity_result2.Result
import rx_activity_result2.RxActivityResult

/**
 * Created by Surya Mahadi on 02/09/2019
 */

class RxEasyImage private constructor(
    private val context: Context,
    private val chooserTitle: String,
    private val folderName: String,
    private val allowMultiple: Boolean,
    private val chooserType: ChooserType,
    private val copyImagesToPublicGalleryFolder: Boolean
) {

    interface Callbacks {
        fun onImagePickerError(error: Throwable, source: MediaSource)

        fun onMediaFilesPicked(imageFiles: Array<MediaFile>, source: MediaSource)

        fun onCanceled(source: MediaSource)
    }

    private var lastCameraFile: MediaFile? = null
    var subject = BehaviorSubject.create<MediaData>()

    private fun getCallerActivity(caller: Any): Activity? = when (caller) {
        is Activity -> caller
        is Fragment -> caller.activity
        is android.app.Fragment -> caller.activity
        else -> null
    }

    private fun cleanup() {
        lastCameraFile?.let { cameraFile ->
            Log.d(EASYIMAGE_LOG_TAG, "Clearing reference to camera file of size: ${cameraFile.file.length()}")
            lastCameraFile = null
        }
    }

    private fun startGallery(caller: Any) : Observable<Result<Activity?>> {
        cleanup()
        val act = getCallerActivity(caller)
        val intent = Intents.createGalleryIntent(allowMultiple)
        return RxActivityResult.on(act).startIntent(intent, RequestCodes.PICK_PICTURE_FROM_GALLERY)
    }

    fun openGallery(activity: Activity) : Observable<Result<Activity?>> = startGallery(activity)

    private fun onPickedExistingPicturesFromLocalStorage(resultIntent: Intent?, activity: Activity) {
        Log.d(EASYIMAGE_LOG_TAG, "Existing picture returned from local storage")
        try {
            val uri = resultIntent!!.data!!
            val photoFile = Files.pickedExistingPicture(activity, uri)
            val mediaFile = MediaFile(uri, photoFile)
            //callbacks.onMediaFilesPicked(arrayOf(mediaFile), MediaSource.DOCUMENTS)
            val mediaData = MediaData()
            mediaData.source = MediaSource.DOCUMENTS
            mediaData.mediaFiles = arrayOf(mediaFile)
            mediaData.status = true
            subject.onNext(mediaData)
        } catch (error: Throwable) {
            error.printStackTrace()
            //callbacks.onImagePickerError(error, MediaSource.DOCUMENTS)
            subject.onError(error)
        } finally {
            subject.onComplete()
        }
        cleanup()
    }

    private fun onPickedExistingPictures(resultIntent: Intent, activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                val clipData = resultIntent.clipData
                val mediaData = MediaData()
                if (clipData != null) {
                    Log.d(EASYIMAGE_LOG_TAG, "Existing picture returned")
                    val files = mutableListOf<MediaFile>()
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        val file = Files.pickedExistingPicture(activity, uri)
                        files.add(MediaFile(uri, file))
                    }
                    if (files.isNotEmpty()) {
                        mediaData.mediaFiles = files.toTypedArray()
                        mediaData.source = MediaSource.GALLERY
                        mediaData.status = true
                        subject.onNext(mediaData)
                        //callbacks.onMediaFilesPicked(files.toTypedArray(), MediaSource.GALLERY)
                    } else {
                        //callbacks.onImagePickerError(EasyImageException("No files were returned from gallery"), MediaSource.GALLERY)
                        mediaData.status = false
                        mediaData.mediaFiles = mutableListOf<MediaFile>().toTypedArray()
                        mediaData.source = MediaSource.GALLERY
                        subject.onNext(mediaData)
                    }
                    cleanup()
                } else {
                    onPickedExistingPicturesFromLocalStorage(resultIntent, activity)
                }
            } else {
                onPickedExistingPicturesFromLocalStorage(resultIntent, activity)
            }
        } catch (error: Throwable) {
            cleanup()
            error.printStackTrace()
            //callbacks.onImagePickerError(error, MediaSource.GALLERY)
            subject.onError(error)
        } finally {
            //subject.onComplete()
        }

    }

    private fun removeCameraFileAndCleanup() {
        lastCameraFile?.file?.let { file ->
            Log.d(EASYIMAGE_LOG_TAG, "Removing camera file of size: ${file.length()}")
            file.delete()
            Log.d(EASYIMAGE_LOG_TAG, "Clearing reference to camera file")
            lastCameraFile = null
        }
    }



    fun handleActivityResult(requestCode: Int, resultCode: Int, resultIntent: Intent?, activity: Activity) : Observable<MediaData> {
        // EasyImage request codes are set to be between 374961 and 374965.
        Log.d("Handle", resultCode.toString() + " " + Activity.RESULT_OK)
        if (requestCode !in 34961..34965) return Observable.just(MediaData())

        val mediaSource = when (requestCode) {
            RequestCodes.PICK_PICTURE_FROM_DOCUMENTS -> MediaSource.DOCUMENTS
            RequestCodes.PICK_PICTURE_FROM_GALLERY -> MediaSource.GALLERY
            RequestCodes.TAKE_PICTURE -> MediaSource.CAMERA_IMAGE
            RequestCodes.CAPTURE_VIDEO -> MediaSource.CAMERA_VIDEO
            else -> MediaSource.CHOOSER
        }

        if (resultCode == Activity.RESULT_OK) {
            //if (requestCode == RequestCodes.PICK_PICTURE_FROM_DOCUMENTS && resultIntent != null) {
            //    onPickedExistingPicturesFromLocalStorage(resultIntent, activity, callbacks)
            //} else
            if (requestCode == RequestCodes.PICK_PICTURE_FROM_GALLERY && resultIntent != null) {
                onPickedExistingPictures(resultIntent, activity)
            }
//            else if (requestCode == RequestCodes.PICK_PICTURE_FROM_CHOOSER) {
//                onFileReturnedFromChooser(resultIntent, activity, callbacks)
//            } else if (requestCode == RequestCodes.TAKE_PICTURE) {
//                onPictureReturnedFromCamera(activity, callbacks)
//            } else if (requestCode == RequestCodes.CAPTURE_VIDEO) {
//                onVideoReturnedFromCamera(activity, callbacks)
//            }
        } else {
            removeCameraFileAndCleanup()
            //callbacks.onCanceled(mediaSource)
        }
        return subject
    }

    //==========================================================================================================================================================================================//

    class Builder(private val context: Context) {
        companion object {
            private fun getAppName(context: Context): String = try {
                context.applicationInfo.loadLabel(context.packageManager).toString()
            } catch (error: Throwable) {
                Log.e(EASYIMAGE_LOG_TAG, "App name couldn't be found. Probably no label was specified in the AndroidManifest.xml. Using EasyImage as a folder name for files.")
                error.printStackTrace()
                "EasyImage"
            }
        }

        private var chooserTitle: String = ""
        private var folderName: String = getAppName(context)
        private var allowMultiple = false
        private var chooserType: ChooserType = ChooserType.CAMERA_AND_DOCUMENTS
        private var copyImagesToPublicGalleryFolder: Boolean = false

        fun setChooserTitle(chooserTitle: String): Builder {
            this.chooserTitle = chooserTitle
            return this
        }

        fun setFolderName(folderName: String): Builder {
            this.folderName = folderName
            return this
        }

        fun setChooserType(chooserType: ChooserType): Builder {
            this.chooserType = chooserType
            return this
        }

        fun allowMultiple(allowMultiple: Boolean): Builder {
            this.allowMultiple = allowMultiple
            return this
        }

        fun setCopyImagesToPublicGalleryFolder(copyImagesToPublicGalleryFolder: Boolean): Builder {
            this.copyImagesToPublicGalleryFolder = copyImagesToPublicGalleryFolder
            return this
        }

        fun build(): RxEasyImage {
            return RxEasyImage(
                context = context,
                chooserTitle = chooserTitle,
                folderName = folderName,
                chooserType = chooserType,
                allowMultiple = allowMultiple,
                copyImagesToPublicGalleryFolder = copyImagesToPublicGalleryFolder
            )
        }
    }
}