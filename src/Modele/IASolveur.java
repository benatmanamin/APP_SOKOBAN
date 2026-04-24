package Modele;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

import Global.Configuration;
import Structures.Sequence;

class IASolveur extends IA {
	private static final int[] DELTA_L = {-1, 1, 0, 0};
	private static final int[] DELTA_C = {0, 0, -1, 1};
	private static final int POIDS_HEURISTIQUE = 2;
	private static final long BASE_HASH = 0x9E3779B97F4A7C15L;
	private static final int ABSENT = Integer.MIN_VALUE;

	@Override
	public Sequence<Coup> joue() {
		PlanSolution plan = calculeSolution();
		Sequence<Coup> resultat = Configuration.nouvelleSequence();

		if (plan == null) {
			return resultat;
		}

		for (int direction : plan.deplacements) {
			Coup coup = niveau.deplace(DELTA_L[direction], DELTA_C[direction]);
			if (coup == null) {
				Configuration.erreur("Le solveur a reconstruit une sequence invalide");
			}
			resultat.insereQueue(coup);
		}
		return resultat;
	}

	private PlanSolution calculeSolution() {
		Grille grille = new Grille(niveau);
		Etat initial = grille.etatInitial();
		Accessibilite accessibiliteInitiale = calculeAccessibilite(grille, initial);
		int heuristiqueInitiale = grille.heuristique(initial);
		if (heuristiqueInitiale >= Grille.INFINI) {
			return null;
		}

		PlanSolution solutionGloutonne = chercheSolution(grille, initial, accessibiliteInitiale, heuristiqueInitiale, true,
				200_000);
		if (solutionGloutonne != null) {
			return solutionGloutonne;
		}
		return chercheSolution(grille, initial, accessibiliteInitiale, heuristiqueInitiale, false, -1);
	}

	private PlanSolution chercheSolution(Grille grille, Etat initial, Accessibilite accessibiliteInitiale,
			int heuristiqueInitiale, boolean glouton, int limiteDeveloppements) {
		PriorityQueue<Noeud> ouverts = new PriorityQueue<>(glouton
				? Comparator.comparingInt((Noeud noeud) -> noeud.heuristique).thenComparingInt(noeud -> noeud.cout)
				: Comparator.comparingInt(Noeud::priorite).thenComparingInt(noeud -> noeud.heuristique));
		LongIntMap meilleursCouts = new LongIntMap();

		long cleDepart = glouton ? initial.cleCaisses() : initial.cle(accessibiliteInitiale.representant);
		Noeud depart = new Noeud(initial, null, null, 0, heuristiqueInitiale, cleDepart);
		ouverts.add(depart);
		meilleursCouts.put(depart.cle, 0);
		int developpements = 0;

		while (!ouverts.isEmpty()) {
			Noeud courant = ouverts.poll();
			int meilleur = meilleursCouts.getOrDefault(courant.cle, ABSENT);
			if ((meilleur != ABSENT) && (courant.cout > meilleur)) {
				continue;
			}
			if (grille.estSolution(courant.etat)) {
				return reconstruitPlan(courant, grille);
			}
			developpements++;
			if ((limiteDeveloppements > 0) && (developpements > limiteDeveloppements)) {
				return null;
			}

			Accessibilite accessibiliteCourante = calculeAccessibilite(grille, courant.etat);
			for (Transition transition : genereTransitions(grille, courant.etat, accessibiliteCourante)) {
				int nouveauCout = courant.cout + transition.cout;
				Accessibilite accessibiliteSuivante = calculeAccessibilite(grille, transition.suivant);
				long cleSuivante = glouton ? transition.suivant.cleCaisses()
						: transition.suivant.cle(accessibiliteSuivante.representant);
				int heuristiqueSuivante = grille.heuristique(transition.suivant);
				if (heuristiqueSuivante >= Grille.INFINI) {
					continue;
				}
				int coutConnu = meilleursCouts.getOrDefault(cleSuivante, ABSENT);
				if ((coutConnu == ABSENT) || (nouveauCout < coutConnu)) {
					meilleursCouts.put(cleSuivante, nouveauCout);
					ouverts.add(new Noeud(transition.suivant, courant, transition, nouveauCout,
							heuristiqueSuivante, cleSuivante));
				}
			}
		}

		return null;
	}

	private Accessibilite calculeAccessibilite(Grille grille, Etat etat) {
		int taille = grille.taille();
		boolean[] visites = new boolean[taille];
		int[] precedent = new int[taille];
		Arrays.fill(precedent, -1);
		int representant = etat.pousseur;

		ArrayDeque<Integer> file = new ArrayDeque<>();
		file.add(etat.pousseur);
		visites[etat.pousseur] = true;

		while (!file.isEmpty()) {
			int position = file.removeFirst();
			int ligne = grille.ligne(position);
			int colonne = grille.colonne(position);

			for (int direction = 0; direction < DELTA_L.length; direction++) {
				int suivanteLigne = ligne + DELTA_L[direction];
				int suivanteColonne = colonne + DELTA_C[direction];
				if (!grille.estDansGrille(suivanteLigne, suivanteColonne)) {
					continue;
				}
				int suivante = grille.index(suivanteLigne, suivanteColonne);
				if (visites[suivante] || grille.estMur(suivante) || etat.aCaisse(suivante)) {
					continue;
				}
				visites[suivante] = true;
				if (suivante < representant) {
					representant = suivante;
				}
				precedent[suivante] = position;
				file.addLast(suivante);
			}
		}

		return new Accessibilite(visites, precedent, representant);
	}

	private ArrayList<Transition> genereTransitions(Grille grille, Etat etat, Accessibilite accessibilite) {
		ArrayList<Transition> transitions = new ArrayList<>();

		for (int indexBoite = 0; indexBoite < etat.caisses.length; indexBoite++) {
			int caisse = etat.caisses[indexBoite];
			int ligne = grille.ligne(caisse);
			int colonne = grille.colonne(caisse);

			for (int direction = 0; direction < DELTA_L.length; direction++) {
				int derriereLigne = ligne - DELTA_L[direction];
				int derriereColonne = colonne - DELTA_C[direction];
				int devantLigne = ligne + DELTA_L[direction];
				int devantColonne = colonne + DELTA_C[direction];

				if (!grille.estDansGrille(derriereLigne, derriereColonne)
						|| !grille.estDansGrille(devantLigne, devantColonne)) {
					continue;
				}

				int derriere = grille.index(derriereLigne, derriereColonne);
				int devant = grille.index(devantLigne, devantColonne);

				if (!accessibilite.atteignable(derriere) || grille.estMur(devant) || etat.aCaisse(devant)) {
					continue;
				}
				if (grille.estCaseMorte(devant)) {
					continue;
				}

				int[] nouvellesCaisses = etat.caisses.clone();
				int positionPousseur = caisse;
				int positionCaisse = devant;
				int nbPoussees = 1;

				while (grille.estDansTunnel(positionCaisse, direction) && !grille.buts[positionCaisse]) {
					int prochaineLigne = grille.ligne(positionCaisse) + DELTA_L[direction];
					int prochaineColonne = grille.colonne(positionCaisse) + DELTA_C[direction];
					if (!grille.estDansGrille(prochaineLigne, prochaineColonne)) {
						break;
					}
					int prochainePosition = grille.index(prochaineLigne, prochaineColonne);
					if (grille.estMur(prochainePosition) || contientCaisse(nouvellesCaisses, indexBoite, prochainePosition)) {
						break;
					}
					positionPousseur = positionCaisse;
					positionCaisse = prochainePosition;
					nbPoussees++;
				}

				nouvellesCaisses[indexBoite] = positionCaisse;
				Arrays.sort(nouvellesCaisses);
				Etat suivant = new Etat(positionPousseur, nouvellesCaisses);
				if (grille.contientCaisseDupliquee(suivant.caisses)) {
					continue;
				}
				if (grille.aBlocageLocal(suivant, positionCaisse) || grille.aBlocageGele(suivant, positionCaisse)
						|| grille.aBlocageCarre(suivant, positionCaisse)) {
					continue;
				}

				transitions.add(new Transition(suivant, caisse, direction, nbPoussees));
			}
		}

		return transitions;
	}

	private int[] reconstruitChemin(int depart, int arrivee, int[] precedent) {
		if (depart == arrivee) {
			return new int[0];
		}

		ArrayList<Integer> directions = new ArrayList<>();
		int courant = arrivee;
		while (courant != depart) {
			int parent = precedent[courant];
			if (parent < 0) {
				return new int[0];
			}
			directions.add(directionEntre(parent, courant));
			courant = parent;
		}

		int[] resultat = new int[directions.size()];
		for (int i = 0; i < directions.size(); i++) {
			resultat[i] = directions.get(directions.size() - 1 - i);
		}
		return resultat;
	}

	private int directionEntre(int depart, int arrivee) {
		int diff = arrivee - depart;
		int colonnes = niveau.colonnes();

		if (diff == -colonnes) {
			return 0;
		}
		if (diff == colonnes) {
			return 1;
		}
		if (diff == -1) {
			return 2;
		}
		if (diff == 1) {
			return 3;
		}
		Configuration.erreur("Chemin du solveur invalide");
		return -1;
	}

	private PlanSolution reconstruitPlan(Noeud but, Grille grille) {
		ArrayList<Noeud> chemin = new ArrayList<>();
		Noeud courant = but;
		while (courant.precedent != null) {
			chemin.add(courant);
			courant = courant.precedent;
		}

		ArrayList<Integer> deplacements = new ArrayList<>();
		for (int i = chemin.size() - 1; i >= 0; i--) {
			Noeud etape = chemin.get(i);
			Etat precedent = etape.precedent.etat;
			Transition transition = etape.transition;
			Accessibilite accessibilite = calculeAccessibilite(grille, precedent);
			int derriere = grille.index(grille.ligne(transition.boiteAvant) - DELTA_L[transition.direction],
					grille.colonne(transition.boiteAvant) - DELTA_C[transition.direction]);
			int[] marche = reconstruitChemin(precedent.pousseur, derriere, accessibilite.precedent);
			for (int pas : marche) {
				deplacements.add(pas);
			}
			for (int j = 0; j < transition.nbPoussees; j++) {
				deplacements.add(transition.direction);
			}
		}

		int[] resultat = new int[deplacements.size()];
		for (int i = 0; i < deplacements.size(); i++) {
			resultat[i] = deplacements.get(i);
		}
		return new PlanSolution(resultat);
	}

	private static class PlanSolution {
		final int[] deplacements;

		PlanSolution(int[] deplacements) {
			this.deplacements = deplacements;
		}
	}

	private static class Noeud {
		final Etat etat;
		final Noeud precedent;
		final Transition transition;
		final int cout;
		final int heuristique;
		final long cle;

		Noeud(Etat etat, Noeud precedent, Transition transition, int cout, int heuristique, long cle) {
			this.etat = etat;
			this.precedent = precedent;
			this.transition = transition;
			this.cout = cout;
			this.heuristique = heuristique;
			this.cle = cle;
		}

		int priorite() {
			return cout + POIDS_HEURISTIQUE * heuristique;
		}
	}

	private static class Transition {
		final Etat suivant;
		final int boiteAvant;
		final int direction;
		final int nbPoussees;
		final int cout;

		Transition(Etat suivant, int boiteAvant, int direction, int nbPoussees) {
			this.suivant = suivant;
			this.boiteAvant = boiteAvant;
			this.direction = direction;
			this.nbPoussees = nbPoussees;
			this.cout = nbPoussees;
		}
	}

	private static class Accessibilite {
		final boolean[] visites;
		final int[] precedent;
		final int representant;

		Accessibilite(boolean[] visites, int[] precedent, int representant) {
			this.visites = visites;
			this.precedent = precedent;
			this.representant = representant;
		}

		boolean atteignable(int position) {
			return visites[position];
		}
	}

	private static class Etat {
		final int pousseur;
		final int[] caisses;
		private long cleCaisses;
		private boolean cleCaissesCalculee;

		Etat(int pousseur, int[] caisses) {
			this.pousseur = pousseur;
			this.caisses = caisses;
		}

		boolean aCaisse(int position) {
			return Arrays.binarySearch(caisses, position) >= 0;
		}

		long cle(int representant) {
			return ajouteHash(cleCaisses(), representant);
		}

		long cleCaisses() {
			if (!cleCaissesCalculee) {
				long hash = BASE_HASH;
				for (int caisse : caisses) {
					hash = ajouteHash(hash, caisse);
				}
				cleCaisses = hash;
				cleCaissesCalculee = true;
			}
			return cleCaisses;
		}
	}

	private static class Grille {
		static final int INFINI = 1_000_000;
		private static final int LIMITE_CACHE_HEURISTIQUE = 200_000;
		final int lignes;
		final int colonnes;
		final boolean[] murs;
		final boolean[] buts;
		final boolean[] casesMortes;
		final int[] positionsButs;
		final int[][] distancesVersButs;
		final int[] caissesInitiales;
		final int pousseurInitial;
		final LongIntMap cacheHeuristique;

		Grille(Niveau niveau) {
			lignes = niveau.lignes();
			colonnes = niveau.colonnes();
			murs = new boolean[lignes * colonnes];
			buts = new boolean[lignes * colonnes];
			ArrayList<Integer> caisses = new ArrayList<>();
			ArrayList<Integer> listeButs = new ArrayList<>();
			int pousseur = -1;

			for (int ligne = 0; ligne < lignes; ligne++) {
				for (int colonne = 0; colonne < colonnes; colonne++) {
					int position = index(ligne, colonne);
					murs[position] = niveau.aMur(ligne, colonne);
					buts[position] = niveau.aBut(ligne, colonne);
					if (buts[position]) {
						listeButs.add(position);
					}
					if (niveau.aCaisse(ligne, colonne)) {
						caisses.add(position);
					}
					if (niveau.aPousseur(ligne, colonne)) {
						pousseur = position;
					}
				}
			}

			caissesInitiales = new int[caisses.size()];
			for (int i = 0; i < caisses.size(); i++) {
				caissesInitiales[i] = caisses.get(i);
			}
			Arrays.sort(caissesInitiales);
			pousseurInitial = pousseur;
			casesMortes = calculeCasesMortes();
			ajouteSegmentsMortsContreMur(casesMortes);
			positionsButs = new int[listeButs.size()];
			for (int i = 0; i < listeButs.size(); i++) {
				positionsButs[i] = listeButs.get(i);
			}
			distancesVersButs = calculeDistancesVersButs();
			cacheHeuristique = new LongIntMap();
		}

		Etat etatInitial() {
			return new Etat(pousseurInitial, caissesInitiales.clone());
		}

		boolean estSolution(Etat etat) {
			for (int caisse : etat.caisses) {
				if (!buts[caisse]) {
					return false;
				}
			}
			return true;
		}

		boolean estMur(int position) {
			return murs[position];
		}

		boolean estCaseMorte(int position) {
			return casesMortes[position];
		}

		int taille() {
			return lignes * colonnes;
		}

		int index(int ligne, int colonne) {
			return ligne * colonnes + colonne;
		}

		int ligne(int position) {
			return position / colonnes;
		}

		int colonne(int position) {
			return position % colonnes;
		}

		boolean estDansGrille(int ligne, int colonne) {
			return (ligne >= 0) && (ligne < lignes) && (colonne >= 0) && (colonne < colonnes);
		}

		boolean contientCaisseDupliquee(int[] caisses) {
			for (int i = 1; i < caisses.length; i++) {
				if (caisses[i - 1] == caisses[i]) {
					return true;
				}
			}
			return false;
		}

		boolean aBlocageLocal(Etat etat, int position) {
			if (buts[position]) {
				return false;
			}
			boolean bloqueHaut = estMur(ligne(position) - 1, colonne(position));
			boolean bloqueBas = estMur(ligne(position) + 1, colonne(position));
			boolean bloqueGauche = estMur(ligne(position), colonne(position) - 1);
			boolean bloqueDroite = estMur(ligne(position), colonne(position) + 1);

			return (bloqueHaut || bloqueBas) && (bloqueGauche || bloqueDroite);
		}

		boolean aBlocageGele(Etat etat, int position) {
			if (buts[position]) {
				return false;
			}
			HashMap<Integer, Boolean> memoHorizontal = new HashMap<>();
			HashMap<Integer, Boolean> memoVertical = new HashMap<>();
			HashMap<Integer, Boolean> enCoursHorizontal = new HashMap<>();
			HashMap<Integer, Boolean> enCoursVertical = new HashMap<>();

			return estBloqueHorizontal(etat, position, memoHorizontal, memoVertical, enCoursHorizontal, enCoursVertical)
					&& estBloqueVertical(etat, position, memoHorizontal, memoVertical, enCoursHorizontal, enCoursVertical);
		}

		boolean aBlocageCarre(Etat etat, int position) {
			if (buts[position]) {
				return false;
			}
			int ligne = ligne(position);
			int colonne = colonne(position);
			int[][] carres = {
					{ligne, colonne, ligne - 1, colonne, ligne, colonne - 1, ligne - 1, colonne - 1},
					{ligne, colonne, ligne - 1, colonne, ligne, colonne + 1, ligne - 1, colonne + 1},
					{ligne, colonne, ligne + 1, colonne, ligne, colonne - 1, ligne + 1, colonne - 1},
					{ligne, colonne, ligne + 1, colonne, ligne, colonne + 1, ligne + 1, colonne + 1}
			};

			for (int[] carre : carres) {
				if (estBlocageCarre(etat, carre)) {
					return true;
				}
			}
			return false;
		}

		int heuristique(Etat etat) {
			long cle = etat.cleCaisses();
			int resultat = cacheHeuristique.getOrDefault(cle, ABSENT);
			if (resultat != ABSENT) {
				return resultat;
			}

			int nbCaisses = etat.caisses.length;
			int nbButs = positionsButs.length;
			int tailleMasque = 1 << nbButs;
			int[] dp = new int[tailleMasque];
			Arrays.fill(dp, INFINI);
			dp[0] = 0;

			for (int indexCaisse = 0; indexCaisse < nbCaisses; indexCaisse++) {
				int[] suivant = new int[tailleMasque];
				Arrays.fill(suivant, INFINI);
				for (int masque = 0; masque < tailleMasque; masque++) {
					if (dp[masque] == INFINI) {
						continue;
					}
					for (int indexBut = 0; indexBut < nbButs; indexBut++) {
						if ((masque & (1 << indexBut)) != 0) {
							continue;
						}
						int distance = distancesVersButs[indexBut][etat.caisses[indexCaisse]];
						if (distance >= INFINI) {
							continue;
						}
						int nouveauMasque = masque | (1 << indexBut);
						int nouveauCout = dp[masque] + distance;
						if (nouveauCout < suivant[nouveauMasque]) {
							suivant[nouveauMasque] = nouveauCout;
						}
					}
				}
				dp = suivant;
			}

			int minimum = INFINI;
			for (int masque = 0; masque < tailleMasque; masque++) {
				if (Integer.bitCount(masque) == nbCaisses && dp[masque] < minimum) {
					minimum = dp[masque];
				}
			}
			if (minimum == INFINI) {
				if (cacheHeuristique.size() < LIMITE_CACHE_HEURISTIQUE) {
					cacheHeuristique.put(cle, INFINI);
				}
				return INFINI;
			}
			for (int caisse : etat.caisses) {
				if (!buts[caisse]) {
					minimum++;
				}
			}
			if (cacheHeuristique.size() < LIMITE_CACHE_HEURISTIQUE) {
				cacheHeuristique.put(cle, minimum);
			}
			return minimum;
		}

		private boolean[] calculeCasesMortes() {
			boolean[] utiles = new boolean[taille()];
			ArrayDeque<Integer> file = new ArrayDeque<>();

			for (int position = 0; position < taille(); position++) {
				if (buts[position]) {
					utiles[position] = true;
					file.addLast(position);
				}
			}

			while (!file.isEmpty()) {
				int position = file.removeFirst();
				int ligne = ligne(position);
				int colonne = colonne(position);

				for (int direction = 0; direction < DELTA_L.length; direction++) {
					int precedenteLigne = ligne - DELTA_L[direction];
					int precedenteColonne = colonne - DELTA_C[direction];
					int pousseurLigne = precedenteLigne - DELTA_L[direction];
					int pousseurColonne = precedenteColonne - DELTA_C[direction];

					if (!estDansGrille(precedenteLigne, precedenteColonne)
							|| !estDansGrille(pousseurLigne, pousseurColonne)) {
						continue;
					}

					int precedente = index(precedenteLigne, precedenteColonne);
					int pousseur = index(pousseurLigne, pousseurColonne);
					if (murs[precedente] || murs[pousseur] || utiles[precedente]) {
						continue;
					}

					utiles[precedente] = true;
					file.addLast(precedente);
				}
			}

			boolean[] mortes = new boolean[taille()];
			for (int position = 0; position < taille(); position++) {
				mortes[position] = !murs[position] && !buts[position] && !utiles[position];
			}
			return mortes;
		}

		private void ajouteSegmentsMortsContreMur(boolean[] mortes) {
			for (int ligne = 0; ligne < lignes; ligne++) {
				for (int colonne = 0; colonne < colonnes; colonne++) {
					marqueSegmentHorizontalContreMur(mortes, ligne, colonne, true);
					marqueSegmentHorizontalContreMur(mortes, ligne, colonne, false);
					marqueSegmentVerticalContreMur(mortes, ligne, colonne, true);
					marqueSegmentVerticalContreMur(mortes, ligne, colonne, false);
				}
			}
		}

		private int[][] calculeDistancesVersButs() {
			int[][] resultat = new int[positionsButs.length][taille()];
			for (int indexBut = 0; indexBut < positionsButs.length; indexBut++) {
				Arrays.fill(resultat[indexBut], INFINI);
				ArrayDeque<Integer> file = new ArrayDeque<>();
				int but = positionsButs[indexBut];
				resultat[indexBut][but] = 0;
				file.addLast(but);

				while (!file.isEmpty()) {
					int position = file.removeFirst();
					int ligne = ligne(position);
					int colonne = colonne(position);

					for (int direction = 0; direction < DELTA_L.length; direction++) {
						int precedenteLigne = ligne - DELTA_L[direction];
						int precedenteColonne = colonne - DELTA_C[direction];
						int pousseurLigne = precedenteLigne - DELTA_L[direction];
						int pousseurColonne = precedenteColonne - DELTA_C[direction];

						if (!estDansGrille(precedenteLigne, precedenteColonne)
								|| !estDansGrille(pousseurLigne, pousseurColonne)) {
							continue;
						}

						int precedente = index(precedenteLigne, precedenteColonne);
						int pousseur = index(pousseurLigne, pousseurColonne);
						if (murs[precedente] || murs[pousseur]) {
							continue;
						}
						if (resultat[indexBut][precedente] <= resultat[indexBut][position] + 1) {
							continue;
						}

						resultat[indexBut][precedente] = resultat[indexBut][position] + 1;
						file.addLast(precedente);
					}
				}
			}
			return resultat;
		}

		private void marqueSegmentHorizontalContreMur(boolean[] mortes, int ligne, int colonne, boolean haut) {
			if (estMur(ligne, colonne) || aBut(ligne, colonne)) {
				return;
			}
			if (!(haut ? estMur(ligne - 1, colonne) : estMur(ligne + 1, colonne))) {
				return;
			}
			if (!estMur(ligne, colonne - 1)) {
				return;
			}

			int fin = colonne;
			boolean objectif = aBut(ligne, fin);
			while (!estMur(ligne, fin + 1) && (haut ? estMur(ligne - 1, fin + 1) : estMur(ligne + 1, fin + 1))) {
				fin++;
				objectif |= aBut(ligne, fin);
			}
			if (!estMur(ligne, fin + 1)) {
				return;
			}
			if (objectif) {
				return;
			}
			for (int c = colonne; c <= fin; c++) {
				mortes[index(ligne, c)] = true;
			}
		}

		private void marqueSegmentVerticalContreMur(boolean[] mortes, int ligne, int colonne, boolean gauche) {
			if (estMur(ligne, colonne) || aBut(ligne, colonne)) {
				return;
			}
			if (!(gauche ? estMur(ligne, colonne - 1) : estMur(ligne, colonne + 1))) {
				return;
			}
			if (!estMur(ligne - 1, colonne)) {
				return;
			}

			int fin = ligne;
			boolean objectif = aBut(fin, colonne);
			while (!estMur(fin + 1, colonne) && (gauche ? estMur(fin + 1, colonne - 1) : estMur(fin + 1, colonne + 1))) {
				fin++;
				objectif |= aBut(fin, colonne);
			}
			if (!estMur(fin + 1, colonne)) {
				return;
			}
			if (objectif) {
				return;
			}
			for (int l = ligne; l <= fin; l++) {
				mortes[index(l, colonne)] = true;
			}
		}

		private boolean aBut(int ligne, int colonne) {
			if (!estDansGrille(ligne, colonne)) {
				return false;
			}
			return buts[index(ligne, colonne)];
		}

		private boolean estMur(int ligne, int colonne) {
			if (!estDansGrille(ligne, colonne)) {
				return true;
			}
			int position = index(ligne, colonne);
			return murs[position];
		}

		private boolean estDansTunnel(int position, int direction) {
			int ligne = ligne(position);
			int colonne = colonne(position);
			if (direction < 2) {
				return estMur(ligne, colonne - 1) && estMur(ligne, colonne + 1);
			}
			return estMur(ligne - 1, colonne) && estMur(ligne + 1, colonne);
		}

		private boolean estBloqueHorizontal(Etat etat, int position, HashMap<Integer, Boolean> memoHorizontal,
				HashMap<Integer, Boolean> memoVertical, HashMap<Integer, Boolean> enCoursHorizontal,
				HashMap<Integer, Boolean> enCoursVertical) {
			Boolean resultat = memoHorizontal.get(position);
			if (resultat != null) {
				return resultat;
			}
			if (enCoursHorizontal.put(position, Boolean.TRUE) != null) {
				return true;
			}

			int ligne = ligne(position);
			int colonne = colonne(position);
			boolean bloqueGauche = estMur(ligne, colonne - 1)
					|| estCaisseBloquanteVerticalement(etat, ligne, colonne - 1, memoHorizontal, memoVertical,
							enCoursHorizontal, enCoursVertical);
			boolean bloqueDroite = estMur(ligne, colonne + 1)
					|| estCaisseBloquanteVerticalement(etat, ligne, colonne + 1, memoHorizontal, memoVertical,
							enCoursHorizontal, enCoursVertical);

			enCoursHorizontal.remove(position);
			boolean bloque = bloqueGauche && bloqueDroite;
			memoHorizontal.put(position, bloque);
			return bloque;
		}

		private boolean estBloqueVertical(Etat etat, int position, HashMap<Integer, Boolean> memoHorizontal,
				HashMap<Integer, Boolean> memoVertical, HashMap<Integer, Boolean> enCoursHorizontal,
				HashMap<Integer, Boolean> enCoursVertical) {
			Boolean resultat = memoVertical.get(position);
			if (resultat != null) {
				return resultat;
			}
			if (enCoursVertical.put(position, Boolean.TRUE) != null) {
				return true;
			}

			int ligne = ligne(position);
			int colonne = colonne(position);
			boolean bloqueHaut = estMur(ligne - 1, colonne)
					|| estCaisseBloquanteHorizontalement(etat, ligne - 1, colonne, memoHorizontal, memoVertical,
							enCoursHorizontal, enCoursVertical);
			boolean bloqueBas = estMur(ligne + 1, colonne)
					|| estCaisseBloquanteHorizontalement(etat, ligne + 1, colonne, memoHorizontal, memoVertical,
							enCoursHorizontal, enCoursVertical);

			enCoursVertical.remove(position);
			boolean bloque = bloqueHaut && bloqueBas;
			memoVertical.put(position, bloque);
			return bloque;
		}

		private boolean estCaisseBloquanteVerticalement(Etat etat, int ligne, int colonne,
				HashMap<Integer, Boolean> memoHorizontal, HashMap<Integer, Boolean> memoVertical,
				HashMap<Integer, Boolean> enCoursHorizontal, HashMap<Integer, Boolean> enCoursVertical) {
			if (!estDansGrille(ligne, colonne)) {
				return false;
			}
			int position = index(ligne, colonne);
			return etat.aCaisse(position) && !buts[position]
					&& estBloqueVertical(etat, position, memoHorizontal, memoVertical, enCoursHorizontal,
							enCoursVertical);
		}

		private boolean estCaisseBloquanteHorizontalement(Etat etat, int ligne, int colonne,
				HashMap<Integer, Boolean> memoHorizontal, HashMap<Integer, Boolean> memoVertical,
				HashMap<Integer, Boolean> enCoursHorizontal, HashMap<Integer, Boolean> enCoursVertical) {
			if (!estDansGrille(ligne, colonne)) {
				return false;
			}
			int position = index(ligne, colonne);
			return etat.aCaisse(position) && !buts[position]
					&& estBloqueHorizontal(etat, position, memoHorizontal, memoVertical, enCoursHorizontal,
							enCoursVertical);
		}

		private boolean estBlocageCarre(Etat etat, int[] carre) {
			int nbObjectifs = 0;
			for (int i = 0; i < carre.length; i += 2) {
				int ligne = carre[i];
				int colonne = carre[i + 1];
				if (!estDansGrille(ligne, colonne)) {
					return false;
				}
				int position = index(ligne, colonne);
				if (!(murs[position] || etat.aCaisse(position))) {
					return false;
				}
				if (buts[position]) {
					nbObjectifs++;
				}
			}
			return nbObjectifs == 0;
		}
	}

	private boolean contientCaisse(int[] caisses, int indexIgnore, int position) {
		for (int i = 0; i < caisses.length; i++) {
			if ((i != indexIgnore) && (caisses[i] == position)) {
				return true;
			}
		}
		return false;
	}

	private static long ajouteHash(long hash, int valeur) {
		long melange = Integer.toUnsignedLong(valeur) + BASE_HASH + (hash << 6) + (hash >>> 2);
		return hash ^ melange;
	}

	private static class LongIntMap {
		private static final float TAUX_MAX = 0.7f;
		private long[] cles;
		private int[] valeurs;
		private boolean[] occupe;
		private int taille;
		private int seuil;

		LongIntMap() {
			this(16);
		}

		LongIntMap(int capaciteInitiale) {
			int capacite = 1;
			while (capacite < capaciteInitiale) {
				capacite <<= 1;
			}
			cles = new long[capacite];
			valeurs = new int[capacite];
			occupe = new boolean[capacite];
			seuil = Math.max(1, (int) (capacite * TAUX_MAX));
		}

		int getOrDefault(long cle, int valeurParDefaut) {
			int index = index(cle);
			while (occupe[index]) {
				if (cles[index] == cle) {
					return valeurs[index];
				}
				index = suivant(index);
			}
			return valeurParDefaut;
		}

		void put(long cle, int valeur) {
			if (taille >= seuil) {
				redimensionne();
			}
			int index = index(cle);
			while (occupe[index]) {
				if (cles[index] == cle) {
					valeurs[index] = valeur;
					return;
				}
				index = suivant(index);
			}
			occupe[index] = true;
			cles[index] = cle;
			valeurs[index] = valeur;
			taille++;
		}

		int size() {
			return taille;
		}

		private void redimensionne() {
			long[] anciennesCles = cles;
			int[] anciennesValeurs = valeurs;
			boolean[] anciensOccupe = occupe;

			int nouvelleCapacite = cles.length << 1;
			cles = new long[nouvelleCapacite];
			valeurs = new int[nouvelleCapacite];
			occupe = new boolean[nouvelleCapacite];
			taille = 0;
			seuil = Math.max(1, (int) (nouvelleCapacite * TAUX_MAX));

			for (int i = 0; i < anciennesCles.length; i++) {
				if (anciensOccupe[i]) {
					put(anciennesCles[i], anciennesValeurs[i]);
				}
			}
		}

		private int index(long cle) {
			long hash = cle ^ (cle >>> 32);
			return (int) hash & (cles.length - 1);
		}

		private int suivant(int index) {
			return (index + 1) & (cles.length - 1);
		}
	}
}
