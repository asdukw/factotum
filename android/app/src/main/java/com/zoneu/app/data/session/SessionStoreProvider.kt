package com.zoneu.app.data.session

import android.content.Context
import com.zoneu.app.ZoneuApplication

/** 从任意 Context 取到应用级的会话库。 */
fun Context.zoneuSessionStore(): SessionStore =
  (applicationContext as ZoneuApplication).sessionStore
