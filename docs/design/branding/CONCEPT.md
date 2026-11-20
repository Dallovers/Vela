# Vela 应用名称与图标

用户要求调用生图能力设计应用图标并改名。随行、帮我办、应手、灵犀、知行均已被用户否定，不能将这些名字写入应用。第一版蓝色抽象光标/对勾已被否定，不作为最终资源。

用户进一步指定“夸克或者英文”的命名风格，本轮采用显示名 **Vela**。图标使用第二版“手机触控”：象牙白立体手机、指尖触控、薄荷绿按钮与深青底。使用内置 image_gen 生成，完整原图为 [vela-icon.png](vela-icon.png)。

0.3.1 已集成 Android 自适应图标：PNG原图不经手工重绘，前景8dp内缩以兼容圆形/圆角遮罩，独立单色矢量用于通知栏和系统主题图标。同步应用名、操作服务名称、授权引导、通知标题与辅助功能描述。包名、组件ID、会话和模型设置沿用原工程。

生成原件保留在 Codex generated_images，项目可独立使用本目录及 app/src/main/res/drawable-nodpi/vela_icon_art.png。当前采用这一方向，不代表用户已对品牌最终定稿。

## 第二版生成提示词

Create a single premium consumer Android app icon for an AI that actually operates the user's phone. The previous abstract cursor/checkmark logo was rejected: this must be concrete, instantly recognizable as PHONE CONTROL, polished and charming. One miniature beautifully crafted smartphone is the hero, upright portrait, mostly front-facing with a gentle 10-degree perspective tilt. Satin ivory ceramic frame, thick soft rounded edges, dark midnight-blue screen. On the display, one large fresh mint circular action button and two broad softly luminous interface lines, no tiny UI. A small sculpted white fingertip comes in from the lower right and visibly taps the mint button on the screen; just enough of the finger to show the touch gesture, no whole hand, no extra fingers. The phone and finger form one coherent compact composition. High-end playful 3D product illustration, soft studio lighting, tactile ceramic and glass, beautiful restrained shadows and gentle highlights. Rich dark teal-blue full-bleed square background with subtle depth, no decorative patterns. Strong silhouette at small launcher size, unmistakable phone and tapping gesture. Entire main composition fits inside the central 64% safe area for Android adaptive circle and squircle masks, with generous breathing room. Square artwork filling the canvas to the edges, NOT a rounded-square tile on a surrounding canvas. Absolutely no words, no letters, no typography, no robot face, no abstract logo, no sparkles, no additional floating bubbles, no excessive gloss, no watermark. Deliver just ONE finished icon, not a presentation or grid.
