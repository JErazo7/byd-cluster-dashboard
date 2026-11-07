/*
 * EvMap — mapa próprio, sem biblioteca e sem chave de API.
 *
 * Tiles raster do OpenStreetMap + rota do OSRM público, desenhados em DOM/canvas:
 *  - os tiles ficam em <img> posicionadas (nada de canvas contaminado por CORS);
 *  - a rota e o carro vão num <canvas> por cima, com a mesma transformação;
 *  - o mapa gira com o carro ou fica com o norte para cima ("northUp").
 *
 *   var map = EvMap.create(rootEl, { zoom: 16, northUp: false });
 *   map.setPose(lat, lon, bearing);                  // ~1 Hz
 *   map.routeTo(lon, lat).then(function (meta) {});  // rota + manobra + ETA
 *   map.draw();
 *
 * Uso pessoal: respeito ao tile usage policy do OSM (User-Agent identificado, sem
 * pré-carregar regiões). O OSRM de demonstração é para uso leve.
 */
(function () {
  'use strict';

  var TILE = 256;
  var MAX_TILES = 56;

  function project(lat, lon, z) {
    var n = Math.pow(2, z);
    var x = (lon + 180) / 360 * n;
    var s = Math.sin(lat * Math.PI / 180);
    var y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n;
    return [x * TILE, y * TILE];
  }

  function create(root, opts) {
    opts = opts || {};
    var zoom = opts.zoom || 16;
    var northUp = opts.northUp !== false;
    var w = 0, h = 0;

    var tiles = document.createElement('div');
    tiles.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;overflow:visible';
    var canvas = document.createElement('canvas');
    canvas.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%';
    root.style.position = 'relative';
    root.style.overflow = 'hidden';
    root.appendChild(tiles);
    root.appendChild(canvas);
    var ctx = canvas.getContext('2d');

    var pose = null;           /* {lat, lon, brg} */
    var route = null;          /* [[lon,lat], ...] */
    var meta = null;
    var cache = {};

    function size() {
      w = root.clientWidth || 1280;
      h = root.clientHeight || 480;
      canvas.width = w;
      canvas.height = h;
    }

    /* ---------- tiles ---------- */
    function drawTiles() {
      if (!pose) return;
      var center = project(pose.lat, pose.lon, zoom);
      var ox = center[0] - w / 2, oy = center[1] - h / 2;
      var z = zoom, n = Math.pow(2, z);
      /* com o mapa girado, os cantos precisam de tiles fora da área visível */
      var pad = northUp ? 0 : Math.ceil(Math.max(w, h) * 0.3);
      var x0 = Math.floor((ox - pad) / TILE), y0 = Math.floor((oy - pad) / TILE);
      var x1 = Math.floor((ox + w + pad) / TILE), y1 = Math.floor((oy + h + pad) / TILE);
      var needed = 0, seen = {};

      for (var x = x0; x <= x1; x++) {
        for (var y = y0; y <= y1; y++) {
          if (y < 0 || y >= n) continue;
          var tx = ((x % n) + n) % n;
          var key = z + '/' + tx + '/' + y;
          seen[key] = 1;
          var img = cache[key];
          if (!img) {
            if (needed++ > MAX_TILES) continue;
            img = document.createElement('img');
            img.src = 'https://tile.openstreetmap.org/' + z + '/' + tx + '/' + y + '.png';
            img.alt = '';
            img.width = TILE;
            img.height = TILE;
            img.style.cssText = 'position:absolute;width:' + TILE + 'px;height:' + TILE +
              'px;image-rendering:auto;-webkit-user-select:none';
            tiles.appendChild(img);
            cache[key] = img;
          }
          img.style.left = Math.round(x * TILE - ox) + 'px';
          img.style.top = Math.round(y * TILE - oy) + 'px';
        }
      }
      /* o que saiu de vista não consome memória */
      for (var k in cache) {
        if (!seen[k] && cache[k].parentNode) {
          cache[k].parentNode.removeChild(cache[k]);
          delete cache[k];
        }
      }
    }

    /* ---------- rota e carro ---------- */
    function drawOverlay() {
      ctx.clearRect(0, 0, w, h);
      if (!pose) return;
      size();
      var center = project(pose.lat, pose.lon, zoom);
      var rot = northUp ? 0 : (-(pose.brg || 0)) * Math.PI / 180;
      var cos = Math.cos(rot), sin = Math.sin(rot);

      function toScreen(lat, lon) {
        var p = project(lat, lon, zoom);
        var dx = p[0] - center[0], dy = p[1] - center[1];
        return [w / 2 + dx * cos - dy * sin, h / 2 + dx * sin + dy * cos];
      }

      if (route && route.length > 1) {
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        /* contorno escuro para a rota aparecer em mapa claro */
        for (var pass = 0; pass < 2; pass++) {
          ctx.strokeStyle = pass ? '#38cfff' : 'rgba(4,10,18,.85)';
          ctx.lineWidth = pass ? 9 : 15;
          ctx.beginPath();
          for (var i = 0; i < route.length; i++) {
            var s = toScreen(route[i][1], route[i][0]);
            if (i === 0) ctx.moveTo(s[0], s[1]); else ctx.lineTo(s[0], s[1]);
          }
          ctx.stroke();
        }
      }

      /* carro: triângulo apontando para o rumo */
      var a = northUp ? (pose.brg || 0) * Math.PI / 180 : 0;
      ctx.save();
      ctx.translate(w / 2, h / 2);
      ctx.rotate(a);
      ctx.beginPath();
      ctx.moveTo(0, -13);
      ctx.lineTo(9, 10);
      ctx.lineTo(0, 6);
      ctx.lineTo(-9, 10);
      ctx.closePath();
      ctx.fillStyle = '#38cfff';
      ctx.strokeStyle = 'rgba(4,10,18,.9)';
      ctx.lineWidth = 2.5;
      ctx.fill();
      ctx.stroke();
      ctx.restore();
    }

    function draw() {
      size();
      drawTiles();
      tiles.style.transform = northUp ? '' : 'rotate(' + (-(pose ? pose.brg || 0 : 0)) + 'deg)';
      tiles.style.transformOrigin = '50% 50%';
      drawOverlay();
    }

    function setPose(lat, lon, brg) {
      pose = { lat: lat, lon: lon, brg: brg || 0 };
    }

    function setRoute(coords, info) {
      route = coords && coords.length > 1 ? coords : null;
      meta = info || null;
    }

    /** rota até um destino (lon, lat) pelo OSRM público */
    function routeTo(lon, lat) {
      if (!pose) return Promise.reject(new Error('sem posição'));
      var url = 'https://router.project-osrm.org/route/v1/driving/' +
        pose.lon.toFixed(6) + ',' + pose.lat.toFixed(6) + ';' +
        lon.toFixed(6) + ',' + lat.toFixed(6) +
        '?overview=full&steps=true&geometries=geojson';
      return fetch(url, { headers: { 'Accept': 'application/json' } })
        .then(function (r) { return r.json(); })
        .then(function (d) {
          if (!d || !d.routes || !d.routes[0]) throw new Error('sem rota');
          var r0 = d.routes[0];
          var step = r0.legs && r0.legs[0] && r0.legs[0].steps && r0.legs[0].steps[0];
          var info = {
            dist: r0.distance,                    /* m */
            dur: r0.duration,                     /* s */
            man: step ? (step.maneuver && step.maneuver.type) : '',
            mod: step ? (step.maneuver && step.maneuver.modifier) : '',
            road: step ? step.name : '',
            nextDist: step ? step.distance : 0
          };
          setRoute(r0.geometry.coordinates, info);
          draw();
          return info;
        });
    }

    window.addEventListener('resize', function () { setTimeout(draw, 50); });
    size();
    return {
      draw: draw, setPose: setPose, setRoute: setRoute, routeTo: routeTo,
      zoom: function (z) { zoom = z; draw(); },
      meta: function () { return meta; }
    };
  }

  window.EvMap = { create: create, project: project };
})();
