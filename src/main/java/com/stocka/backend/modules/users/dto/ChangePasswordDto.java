package com.stocka.backend.modules.users.dto;

/**
 * Datos de entrada para el endpoint {@code PATCH /users/me/password},
 * con el que un usuario autenticado cambia su propia contraseña.
 *
 * <p>Las validaciones (no nulos, longitud mínima, coincidencia de
 * {@code newPassword} y {@code repeatPassword}, comprobación de
 * {@code currentPassword} contra el hash almacenado) se ejecutan en el
 * service correspondiente; este DTO sólo transporta los valores en bruto.
 */
public class ChangePasswordDto {

    private String currentPassword;
    private String newPassword;
    private String repeatPassword;

    /**
     * @return contraseña actual en texto plano para verificar identidad
     */
    public String getCurrentPassword() {
        return currentPassword;
    }

    /**
     * @param currentPassword contraseña actual en texto plano
     * @return esta misma instancia (estilo fluido)
     */
    public ChangePasswordDto setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
        return this;
    }

    /**
     * @return nueva contraseña que sustituirá a la actual
     */
    public String getNewPassword() {
        return newPassword;
    }

    /**
     * @param newPassword nueva contraseña en texto plano
     * @return esta misma instancia (estilo fluido)
     */
    public ChangePasswordDto setNewPassword(String newPassword) {
        this.newPassword = newPassword;
        return this;
    }

    /**
     * @return repetición de la nueva contraseña, debe coincidir con {@link #getNewPassword()}
     */
    public String getRepeatPassword() {
        return repeatPassword;
    }

    /**
     * @param repeatPassword repetición de la nueva contraseña
     * @return esta misma instancia (estilo fluido)
     */
    public ChangePasswordDto setRepeatPassword(String repeatPassword) {
        this.repeatPassword = repeatPassword;
        return this;
    }
}
