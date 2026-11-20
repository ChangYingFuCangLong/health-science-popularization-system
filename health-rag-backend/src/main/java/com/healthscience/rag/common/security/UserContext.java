package com.healthscience.rag.common.security;

import com.healthscience.rag.common.exception.BizException;

/**
 * 当前登录用户的线程级上下文。
 *
 * <p>为什么还要这一层（而不是直接用 {@code SecurityContextHolder}）：
 * 业务层只依赖本类，不引入任何安全框架类型，与架构约束 C1/C2 保持一致。
 *
 * <p><b>虚拟线程下的注意事项</b>：ThreadLocal 在虚拟线程上同样有效（每个虚拟线程有独立实例），
 * 但虚拟线程会被池化复用，因此<b>必须在过滤器的 finally 中清理</b>，否则会串号。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 当前用户 ID；未登录时为 null */
    public static Long currentId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.id();
    }

    /** 当前用户 ID；未登录时抛业务异常（用于必须登录的接口） */
    public static Long requireId() {
        Long id = currentId();
        if (id == null) {
            throw BizException.unauthorized("未登录或 token 失效");
        }
        return id;
    }

    public static boolean isAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && user.isAdmin();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
