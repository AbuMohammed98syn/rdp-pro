package com.rdppro.rdpro

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.fragment.app.Fragment

class CameraFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_camera, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        val rdp = (activity as MainActivity).rdp
        v.findViewById<TextView>(R.id.tvCameraUrl).text = "http://${rdp.host}:${rdp.port}/mobile"
    }
}
