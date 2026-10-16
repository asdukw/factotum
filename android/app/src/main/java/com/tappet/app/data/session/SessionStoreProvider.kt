package com.tappet.app.data.session

import android.content.Context
import com.tappet.app.TappetApplication

/** 从任意 Context 取到应用级的会话库。 */
fun Context.tappetSessionStore(): SessionStore =
  (applicationContext as TappetApplication).sessionStore
