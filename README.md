# CompatRippleDrawable
[![API](https://img.shields.io/badge/API-16%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=16)

`Android Pie` Style RippleDrawable

## Setup
Including file in your project
```
CompatRippleDrawble.java
```

## Usage
Use static setup any `View`

``` Java
Drawable drawable = new CompatRippleDrawable.Builder()
        .setCornerRadius(/* Corner Radius */)
        .setColor(/* Ripple Color */)
        .setAlpha(/* Ripple Alpha */)
        .build();
        
view.setBackground(drawable);
```

## Support for Android api versions < 21 (LOLLIPOP)
``` Java
// Extended view touch event override
@Override
public boolean onTouchEvent(MotionEvent event) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
        /* drawable */.setHotspot(event.getX(), event.getY());
    }
    return super.onTouchEvent(event);
}

// touch event listener
/* view */.setOnTouchListener(new OnTouchListener() {
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            /* drawable */.setHotspot(event.getX(), event.getY());
        }
        return false;
    }
});
```

## Preview
![Preview](https://github.com/SteaI/CompatRippleDrawable/blob/master/resource/preview.gif?raw=true)
