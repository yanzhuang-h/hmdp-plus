package org.javaup.utils;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 系统常量-黑马点评普通版本和plus版本使用
 * @author: 阿星不是程序员
 **/
public class SystemConstants {
    /**
     * 图片上传目录。
     * 指向 Vue3 前端项目的静态资源目录：前端各页面用 '/src/assets' + 后端返回的 '/imgs/...' 拼接图片地址，
     * 由 Vite dev server 直接提供这些文件，因此上传必须落到这里才能被前端读到。
     * 注意：仅适用于开发阶段；打包后 src 目录不存在，生产环境应改为 nginx 静态目录并调整前端拼接方式。
     */
    public static final String IMAGE_UPLOAD_DIR = "D:\\Java\\hmdp-plus\\hmdp-vue3\\src\\assets\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
