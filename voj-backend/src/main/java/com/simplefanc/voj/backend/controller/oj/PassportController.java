package com.simplefanc.voj.backend.controller.oj;

import com.simplefanc.voj.backend.pojo.dto.ApplyResetPasswordDTO;
import com.simplefanc.voj.backend.pojo.dto.LoginDTO;
import com.simplefanc.voj.backend.pojo.dto.RegisterDTO;
import com.simplefanc.voj.backend.pojo.dto.ResetPasswordDTO;
import com.simplefanc.voj.backend.pojo.vo.RegisterCodeVO;
import com.simplefanc.voj.backend.pojo.vo.UserInfoVO;
import com.simplefanc.voj.backend.service.account.PassportService;
import com.simplefanc.voj.backend.shiro.UserSessionUtil;
import com.simplefanc.voj.common.result.CommonResult;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @Author: chenfan
 * @Date: 2022/3/11 17:00
 * @Description: 处理登录、注册、重置密码
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PassportController {

    private final PassportService passportService;

    /**
     * @param loginDTO
     * @MethodName login
     * @Description 处理登录逻辑
     * @Return CommonResult
     * @Since 2021/10/24
     */
    @PostMapping("/login")
    public CommonResult<UserInfoVO> login(@Validated @RequestBody LoginDTO loginDTO, HttpServletResponse response,
                                          HttpServletRequest request) {
        return CommonResult.successResponse(passportService.login(loginDTO, response, request));
    }

    /**
     * @MethodName getRegisterCode
     * @Description 调用邮件服务，发送注册流程的6位随机验证码
     * @Return
     * @Since 2021/10/26
     */
    @RequestMapping(value = "/get-register-code", method = RequestMethod.GET)
    public CommonResult<RegisterCodeVO> getRegisterCode(@RequestParam(value = "email") String email) {
        return CommonResult.successResponse(passportService.getRegisterCode(email));
    }

    /**
     * @param registerDTO
     * @MethodName register
     * @Description 注册逻辑，具体参数请看RegisterDTO类
     * @Return
     * @Since 2021/10/24
     */
    @PostMapping("/register")
    public CommonResult<Void> register(@Validated @RequestBody RegisterDTO registerDTO) {
        passportService.register(registerDTO);
        return CommonResult.successResponse();
    }

    /**
     * @param applyResetPasswordDTO
     * @MethodName applyResetPassword
     * @Description 发送重置密码的链接邮件
     * @Return
     * @Since 2021/11/6
     */
    @PostMapping("/apply-reset-password")
    public CommonResult<Void> applyResetPassword(@RequestBody ApplyResetPasswordDTO applyResetPasswordDTO) {
        passportService.applyResetPassword(applyResetPasswordDTO);
        return CommonResult.successResponse();
    }

    /**
     * @param resetPasswordDTO
     * @MethodName resetPassword
     * @Description 用户重置密码
     * @Return
     * @Since 2021/11/6
     */
    @PostMapping("/reset-password")
    public CommonResult<Void> resetPassword(@RequestBody ResetPasswordDTO resetPasswordDTO) {
        passportService.resetPassword(resetPasswordDTO);
        return CommonResult.successResponse();
    }

    /**
     * @MethodName logout
     * @Description 退出逻辑，将jwt在redis中清除，下次需要再次登录。
     * @Return CommonResult
     * @Since 2021/10/24
     */
    @GetMapping("/logout")
    @RequiresAuthentication
    public CommonResult<Void> logout() {
        UserSessionUtil.logout();
        return CommonResult.successResponse("登出成功！");
    }

}