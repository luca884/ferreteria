package com.ferreteria.plataforma.service;

import java.util.List;

/** El estado general de la plataforma: totales de ferreterías activas y suspendidas, y cada una. */
public record EstadoGeneral(long activas, long suspendidas, List<FerreteriaAdmin> ferreterias) {}
