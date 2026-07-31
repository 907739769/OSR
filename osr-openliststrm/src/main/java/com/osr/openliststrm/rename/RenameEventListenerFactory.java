package com.osr.openliststrm.rename;

/**
 * 重命名事件监听器工厂
 *
 * @author: Jack
 * @creat: 2026/1/13 11:13
 */
public interface RenameEventListenerFactory {

    RenameEventListener create(Integer taskId);

}
