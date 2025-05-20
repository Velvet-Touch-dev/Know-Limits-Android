package com.velvettouch.nosafeword

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class AddPositionDialogFragment : DialogFragment() {

    private lateinit var positionNameEditText: TextInputEditText
    private lateinit var positionNameInputLayout: TextInputLayout
    private lateinit var positionImagePreview: ImageView
    private var selectedImageUri: Uri? = null

    interface AddPositionDialogListener {
        fun onPositionAdded(name: String, imageUri: Uri?)
    }
    var listener: AddPositionDialogListener? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            selectedImageUri = result.data?.data
            positionImagePreview.setImageURI(selectedImageUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.App_Material3_DialogFragment)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_add_position, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        positionNameEditText = view.findViewById(R.id.edit_text_position_name)
        positionNameInputLayout = view.findViewById(R.id.text_input_layout_position_name)
        positionImagePreview = view.findViewById(R.id.add_position_image_preview)
        val selectImageButton: Button = view.findViewById(R.id.button_select_image)
        val saveButton: Button = view.findViewById(R.id.button_save_position)
        val cancelButton: Button = view.findViewById(R.id.button_cancel_add_position)

        selectImageButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }

        saveButton.setOnClickListener {
            val name = positionNameEditText.text.toString().trim()
            if (name.isEmpty()) {
                positionNameInputLayout.error = "Position name cannot be empty"
                return@setOnClickListener
            }
            if (selectedImageUri == null) {
                Toast.makeText(context, "Please select an image", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            positionNameInputLayout.error = null
            listener?.onPositionAdded(name, selectedImageUri)
            dismiss()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}