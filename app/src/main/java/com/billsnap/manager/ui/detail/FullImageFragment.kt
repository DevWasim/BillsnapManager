package com.billsnap.manager.ui.detail

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.databinding.FragmentFullImageBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.io.File

/**
 * Full-screen image viewer with pinch-to-zoom and double-tap zoom.
 * Receives the imagePath argument and displays the image.
 * resetZoom() is called inside the Glide listener so that the matrix
 * is applied only after the bitmap is fully loaded — fixing the
 * half-image / offset display bug.
 */
class FullImageFragment : Fragment() {

    private var _binding: FragmentFullImageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFullImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imagePath = arguments?.getString("imagePath") ?: run {
            findNavController().popBackStack(); return
        }

        // Load the image and call resetZoom() AFTER Glide sets the drawable
        val file = File(imagePath)
        Glide.with(this)
            .load(if (file.exists()) file else null)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?,
                    target: Target<Drawable>, isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Drawable, model: Any,
                    target: Target<Drawable>, dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    // Post to ensure the view has been laid out before computing the matrix
                    binding.zoomableImage.post {
                        binding.zoomableImage.resetZoom()
                    }
                    return false // let Glide set the drawable normally
                }
            })
            .into(binding.zoomableImage)

        binding.btnClose.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
