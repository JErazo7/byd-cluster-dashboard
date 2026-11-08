/*
 * Motores do SharkLab — cada item da lista é um som completo, em texto.
 *
 * Para criar/modificar: copie um bloco, mude os números, salve. O app relê este arquivo
 * a cada abertura (e o editor ao vivo, no botão "recarregar" da página de teste).
 *
 * CAMPOS
 *   id          nome curto usado na URL/preferência (sem espaço)
 *   name        o que aparece na tela
 *   cylinders   número de cilindros (define a frequência das explosões)
 *   idle        giro de marcha lenta (rpm)
 *   redline     giro de potência máxima (rpm)
 *   limiter     corte de giro (rpm)
 *   gears       relações de câmbio (1ª → 6ª)
 *   finalDrive  diferencial
 *   maxAccel    aceleração a fundo em 1ª (m/s²)
 *   torque      curva de torque [[rpm, 0..1], ...]
 *   harmonics   peso de cada harmônico {1: 1.0, 2: 0.6, ...} — é o TIMBRE
 *               (mais graves = encorpado; mais agudos = gritado)
 *   pulseSharp  brilho quando harmonics não lista o harmônico (1.0 = macio, 2 = seco)
 *   harmonicsMax  corta acima deste harmônico
 *   exhaustCut  [Hz no idle, Hz no corte] — abertura do escape conforme o giro
 *   filterQ     ressonância do filtro (0.7 macio, 2.5 estridente)
 *   intake      [ganho no idle, ganho a fundo] — ruído de admissão
 *   turbo       0..1 assobio de turbo
 *   pops        0..1 estouros ao tirar o pé
 *   oscMix      mistura dos dois osciladores (0.5 = meio a meio; dá a irregularidade)
 *   drive       saturação (0 = limpo, 1 = rascante)
 */
window.ENGINES = [
  {
    id: 'v8_muscle',
    name: 'V8 Muscle',
    cylinders: 8,
    idle: 620,
    redline: 6200,
    limiter: 6400,
    gears: [3.2, 2.1, 1.5, 1.15, 0.92, 0.75],
    finalDrive: 3.7,
    maxAccel: 5.0,
    torque: [[700, 0.6], [2200, 0.95], [3800, 1], [5200, 0.9], [6200, 0.72]],
    harmonics: { 1: 1.0, 2: 0.72, 3: 0.5, 4: 0.36, 5: 0.2, 6: 0.24, 7: 0.1, 8: 0.14 },
    pulseSharp: 1.3,
    exhaustCut: [95, 620],
    filterQ: 1.25,
    intake: [0.01, 0.1],
    turbo: 0,
    pops: 0.85,
    oscMix: 0.42,
    drive: 0.7
  },
  {
    id: 'i6_turbo',
    name: 'Inline-6 Turbo',
    cylinders: 6,
    idle: 700,
    redline: 7000,
    limiter: 7200,
    gears: [3.6, 2.2, 1.55, 1.15, 0.9, 0.74],
    finalDrive: 3.45,
    maxAccel: 4.8,
    torque: [[800, 0.5], [1800, 0.85], [2600, 1], [4500, 0.97], [7000, 0.8]],
    harmonics: { 1: 0.9, 2: 0.8, 3: 0.62, 4: 0.4, 5: 0.3, 6: 0.22, 7: 0.16, 8: 0.12, 9: 0.08 },
    pulseSharp: 1.1,
    exhaustCut: [120, 900],
    filterQ: 1.0,
    intake: [0.02, 0.22],
    turbo: 0.9,
    pops: 0.5,
    oscMix: 0.5,
    drive: 0.55
  },
  {
    id: 'flat6_9000',
    name: 'Flat-Six 9000',
    cylinders: 6,
    idle: 850,
    redline: 9000,
    limiter: 9200,
    gears: [3.5, 2.3, 1.7, 1.3, 1.05, 0.86],
    finalDrive: 3.9,
    maxAccel: 4.6,
    torque: [[900, 0.45], [2500, 0.7], [4500, 0.9], [6500, 1], [9000, 0.88]],
    harmonics: { 1: 0.75, 2: 0.55, 3: 0.5, 4: 0.45, 5: 0.38, 6: 0.34, 7: 0.26, 8: 0.22, 9: 0.16, 10: 0.12 },
    pulseSharp: 0.9,
    exhaustCut: [180, 1800],
    filterQ: 0.8,
    intake: [0.03, 0.3],
    turbo: 0,
    pops: 0.25,
    oscMix: 0.62,
    drive: 0.35
  },
  {
    id: 'v12_gt',
    name: 'V12 GT',
    cylinders: 12,
    idle: 600,
    redline: 8000,
    limiter: 8200,
    gears: [3.1, 2.0, 1.45, 1.1, 0.9, 0.76],
    finalDrive: 3.4,
    maxAccel: 4.4,
    torque: [[700, 0.55], [2000, 0.85], [4000, 0.97], [6000, 1], [8000, 0.9]],
    harmonics: { 1: 1.0, 2: 0.5, 3: 0.34, 4: 0.22, 5: 0.14, 6: 0.1, 7: 0.07, 8: 0.05 },
    pulseSharp: 1.5,
    exhaustCut: [80, 780],
    filterQ: 1.4,
    intake: [0.008, 0.07],
    turbo: 0,
    pops: 0.15,
    oscMix: 0.58,
    drive: 0.45
  },

  {
    id: 'v8_sc',
    name: 'V8 Supercharged',
    cylinders: 8,
    idle: 650,
    redline: 6400,
    limiter: 6600,
    gears: [3.1, 2.0, 1.45, 1.1, 0.9, 0.74],
    finalDrive: 3.55,
    maxAccel: 5.6,
    torque: [[700, 0.7], [2000, 0.95], [3500, 1], [5000, 0.94], [6400, 0.8]],
    harmonics: { 1: 1.0, 2: 0.8, 3: 0.55, 4: 0.42, 5: 0.26, 6: 0.3, 7: 0.14, 8: 0.18 },
    pulseSharp: 1.25,
    exhaustCut: [100, 700],
    filterQ: 1.3,
    intake: [0.012, 0.12],
    turbo: 1.0,
    pops: 0.7,
    oscMix: 0.4,
    drive: 0.8
  },
  {
    id: 'v8_flatplane',
    name: 'V8 Flat-Plane',
    cylinders: 8,
    idle: 800,
    redline: 8500,
    limiter: 8700,
    gears: [3.3, 2.2, 1.6, 1.25, 1.0, 0.82],
    finalDrive: 3.9,
    maxAccel: 5.2,
    torque: [[800, 0.5], [2500, 0.75], [4500, 0.92], [6500, 1], [8500, 0.9]],
    harmonics: { 1: 0.85, 2: 0.6, 3: 0.55, 4: 0.5, 5: 0.42, 6: 0.38, 7: 0.3, 8: 0.26, 9: 0.2 },
    pulseSharp: 0.95,
    exhaustCut: [160, 1500],
    filterQ: 0.9,
    intake: [0.02, 0.24],
    turbo: 0,
    pops: 0.5,
    oscMix: 0.6,
    drive: 0.45
  },
  {
    id: 'v10_supercar',
    name: 'V10 Supercar',
    cylinders: 10,
    idle: 900,
    redline: 8500,
    limiter: 8700,
    gears: [3.4, 2.3, 1.7, 1.3, 1.05, 0.86],
    finalDrive: 3.8,
    maxAccel: 5.4,
    torque: [[900, 0.5], [3000, 0.8], [5500, 1], [7500, 0.95], [8500, 0.88]],
    harmonics: { 1: 0.8, 2: 0.6, 3: 0.55, 4: 0.5, 5: 0.44, 6: 0.4, 7: 0.32, 8: 0.28, 9: 0.2, 10: 0.16 },
    pulseSharp: 0.85,
    exhaustCut: [200, 2000],
    filterQ: 0.75,
    intake: [0.03, 0.3],
    turbo: 0,
    pops: 0.35,
    oscMix: 0.62,
    drive: 0.35
  },
  {
    id: 'i5_25t',
    name: 'Inline-5 2.5T',
    cylinders: 5,
    idle: 780,
    redline: 7000,
    limiter: 7200,
    gears: [3.7, 2.2, 1.5, 1.15, 0.9],
    finalDrive: 3.6,
    maxAccel: 4.9,
    torque: [[800, 0.55], [1800, 0.9], [2500, 1], [4500, 0.95], [7000, 0.78]],
    harmonics: { 1: 0.95, 2: 0.7, 3: 0.45, 4: 0.3, 5: 0.34, 6: 0.2, 7: 0.1, 8: 0.12, 9: 0.06 },
    pulseSharp: 1.35,
    exhaustCut: [110, 800],
    filterQ: 1.2,
    intake: [0.02, 0.2],
    turbo: 0.85,
    pops: 0.6,
    oscMix: 0.45,
    drive: 0.6
  },
  {
    id: '2jz_twinturbo',
    name: '2JZ Twin Turbo',
    cylinders: 6,
    idle: 720,
    redline: 7200,
    limiter: 7400,
    gears: [3.8, 2.3, 1.5, 1.1, 0.86, 0.72],
    finalDrive: 3.7,
    maxAccel: 5.0,
    torque: [[800, 0.45], [2200, 0.8], [3200, 1], [5000, 0.98], [7200, 0.8]],
    harmonics: { 1: 0.9, 2: 0.72, 3: 0.55, 4: 0.4, 5: 0.3, 6: 0.22, 7: 0.14, 8: 0.1 },
    pulseSharp: 1.1,
    exhaustCut: [130, 1000],
    filterQ: 1.05,
    intake: [0.025, 0.28],
    turbo: 1.0,
    pops: 0.9,
    oscMix: 0.5,
    drive: 0.55
  },
  {
    id: 'boxer4_rally',
    name: 'Boxer 4 Rally',
    cylinders: 4,
    idle: 900,
    redline: 6800,
    limiter: 7000,
    gears: [3.9, 2.4, 1.7, 1.3, 1.0],
    finalDrive: 4.1,
    maxAccel: 4.6,
    torque: [[900, 0.5], [2000, 0.85], [3200, 1], [5000, 0.92], [6800, 0.78]],
    harmonics: { 1: 0.8, 2: 0.7, 3: 0.5, 4: 0.45, 5: 0.3, 6: 0.25 },
    pulseSharp: 1.15,
    exhaustCut: [120, 850],
    filterQ: 1.15,
    intake: [0.03, 0.26],
    turbo: 0.9,
    pops: 1.0,
    oscMix: 0.4,
    drive: 0.7
  },
  {
    id: 'rotary_2rotor',
    name: 'Rotary 2-Rotor',
    cylinders: 4,
    idle: 900,
    redline: 9000,
    limiter: 9200,
    gears: [3.6, 2.2, 1.6, 1.25, 1.0, 0.84],
    finalDrive: 4.1,
    maxAccel: 4.2,
    torque: [[900, 0.4], [3000, 0.6], [5000, 0.85], [7000, 1], [9000, 0.95]],
    harmonics: { 1: 0.7, 2: 0.6, 3: 0.62, 4: 0.55, 5: 0.5, 6: 0.45, 7: 0.4, 8: 0.3, 9: 0.24, 10: 0.2 },
    pulseSharp: 0.7,
    exhaustCut: [220, 2400],
    filterQ: 0.7,
    intake: [0.04, 0.34],
    turbo: 0,
    pops: 0.45,
    oscMix: 0.66,
    drive: 0.3
  },
  {
    id: 'shark_15t',
    name: 'Shark 1.5T (PHEV)',
    cylinders: 4,
    idle: 750,
    redline: 6000,
    limiter: 6200,
    gears: [3.9, 2.3, 1.5, 1.1, 0.86, 0.7],
    finalDrive: 3.9,
    maxAccel: 3.6,
    torque: [[800, 0.5], [1500, 0.9], [2300, 1], [4000, 0.85], [6000, 0.7]],
    harmonics: { 1: 0.85, 2: 0.55, 3: 0.3, 4: 0.2, 5: 0.12, 6: 0.08 },
    pulseSharp: 1.35,
    exhaustCut: [110, 700],
    filterQ: 1.1,
    intake: [0.015, 0.14],
    turbo: 0.65,
    pops: 0.3,
    oscMix: 0.48,
    drive: 0.45
  }
];
